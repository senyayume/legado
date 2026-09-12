package io.legado.app.model.read

import io.legado.app.help.config.ReadStyleConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderPaletteTab { READER, HIGHLIGHT }

enum class ReaderPaletteDraftStatus { EDITING, APPLYING, APPLIED, CANCELLED }

class ReaderPaletteDraftUnavailable(val status: ReaderPaletteDraftStatus) : IllegalStateException()

/** Mutable configuration objects never escape a published draft state. */
class ReaderPaletteDraftState internal constructor(
    config: ReadStyleConfig,
    val status: ReaderPaletteDraftStatus = ReaderPaletteDraftStatus.EDITING,
    val failure: Throwable? = null
) {
    private val config = config.detachedAppearanceCopy()

    fun palette(mode: ReaderPaletteMode): ReaderPalette = config.readerPalette.copyForMode(mode)

    fun snapshot(): ReadStyleConfig = config.detachedAppearanceCopy()
}

/** One editing session. The caller owns persistence and closes the UI only after success. */
class ReaderPaletteDraft(initial: ReadStyleConfig) {
    private val mutableState = MutableStateFlow(ReaderPaletteDraftState(initial))
    val state: StateFlow<ReaderPaletteDraftState> = mutableState.asStateFlow()

    fun update(mode: ReaderPaletteMode, palette: ReaderPalette) {
        edit { it.readerPalette.setForMode(mode, palette.copy()) }
    }

    fun setTextColor(mode: ReaderPaletteMode, color: Int) = edit {
        it.setTextColorForMode(mode, color)
    }

    fun setBackground(mode: ReaderPaletteMode, type: Int, source: String) = edit {
        if (type in 0..2 && source.isNotBlank()) {
            if (type != 0 && it.backgroundTypeForMode(mode) == 0) {
                it.setBackgroundSettingsForMode(mode, it.backgroundSettingsForMode(mode).copy(
                    baseColor = it.bgColorForMode(mode) or 0xff000000.toInt()
                ))
            }
            it.setBackgroundForMode(mode, type, source)
        }
    }

    fun setBackgroundAlpha(alpha: Int) = edit { it.bgAlpha = alpha.coerceIn(0, 100) }

    fun setBackgroundColor(mode: ReaderPaletteMode, color: Int) = edit {
        val opaque = color or 0xff000000.toInt()
        if (it.backgroundTypeForMode(mode) == 0) {
            it.setBackgroundForMode(mode, 0, "#${opaque.toUInt().toString(16)}")
        } else {
            it.setBackgroundSettingsForMode(mode, it.backgroundSettingsForMode(mode).copy(baseColor = opaque))
        }
    }

    fun removeBackgroundImage(mode: ReaderPaletteMode) = edit {
        val base = it.bgColorForMode(mode) or 0xff000000.toInt()
        it.setBackgroundForMode(mode, 0, "#${base.toUInt().toString(16)}")
    }

    fun updateBackgroundSettings(mode: ReaderPaletteMode, settings: ReaderBackgroundSettings) = edit {
        it.setBackgroundSettingsForMode(mode, settings)
    }

    fun randomizeTextColor(mode: ReaderPaletteMode) = edit {
        it.setTextColorForMode(mode, FontColorGenerator.next(
            previous = it.textColorForMode(mode), background = it.bgColorForMode(mode)
        ))
    }

    fun applyPreset(mode: ReaderPaletteMode, preset: ReaderPalettePreset) = edit {
        it.setTextColorForMode(mode, preset.body)
        it.setBackgroundForMode(mode, 0, preset.background.toUInt().toString(16).padStart(8, '0').let { hex -> "#$hex" })
        it.readerPalette.setForMode(mode, it.readerPalette.forMode(mode).resetReaderFields(preset.palette()))
    }

    fun reset(mode: ReaderPaletteMode, tab: ReaderPaletteTab) {
        edit { config ->
            val palettes = config.readerPalette
            val defaults = ReaderPaletteSet().forMode(mode)
            val current = palettes.forMode(mode)
            palettes.setForMode(mode, when (tab) {
                ReaderPaletteTab.READER -> current.resetReaderFields(defaults)
                ReaderPaletteTab.HIGHLIGHT -> current.resetHighlightFields(defaults)
            })
            if (tab == ReaderPaletteTab.READER) {
                val base = ReadStyleConfig()
                config.setTextColorForMode(mode, base.textColorForMode(mode))
                config.setBackgroundForMode(mode, base.backgroundTypeForMode(mode), base.backgroundForMode(mode))
                config.setBackgroundSettingsForMode(mode, ReaderBackgroundSettings())
            }
        }
    }

    fun cancel() {
        while (true) {
            val current = mutableState.value
            if (current.status != ReaderPaletteDraftStatus.EDITING) return
            if (mutableState.compareAndSet(current, ReaderPaletteDraftState(
                    current.snapshot(), ReaderPaletteDraftStatus.CANCELLED
                ))) return
        }
    }

    suspend fun apply(onApply: suspend (ReadStyleConfig) -> Result<Unit>): Result<Unit> {
        val current = mutableState.value
        if (current.status != ReaderPaletteDraftStatus.EDITING) {
            return Result.failure(ReaderPaletteDraftUnavailable(current.status))
        }
        val applying = ReaderPaletteDraftState(current.snapshot(), ReaderPaletteDraftStatus.APPLYING)
        if (!mutableState.compareAndSet(current, applying)) {
            return Result.failure(ReaderPaletteDraftUnavailable(mutableState.value.status))
        }
        val result = try {
            onApply(applying.snapshot()).also {
                val error = it.exceptionOrNull()
                if (error is CancellationException) throw error
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = ReaderPaletteDraftState(applying.snapshot())
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        mutableState.value = ReaderPaletteDraftState(
            applying.snapshot(),
            if (result.isSuccess) ReaderPaletteDraftStatus.APPLIED else ReaderPaletteDraftStatus.EDITING,
            result.exceptionOrNull()
        )
        return result
    }

    private inline fun edit(change: (ReadStyleConfig) -> Unit) {
        while (true) {
            val current = mutableState.value
            if (current.status != ReaderPaletteDraftStatus.EDITING) return
            val updated = current.snapshot().also(change)
            if (mutableState.compareAndSet(current, ReaderPaletteDraftState(updated))) return
        }
    }
}

fun ReadStyleConfig.detachedAppearanceCopy() = copy(readerPalette = ReaderPaletteSet(readerPalette.day.copy(), readerPalette.night.copy(), readerPalette.eInk.copy()))
