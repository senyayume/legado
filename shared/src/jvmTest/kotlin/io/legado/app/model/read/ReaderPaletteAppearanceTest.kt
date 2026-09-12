package io.legado.app.model.read

import io.legado.app.help.config.ReadStyleConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderPaletteAppearanceTest {
    @Test fun appearanceAndSemanticColorsAreOneIsolatedTransaction() = runBlocking {
        val original = ReadStyleConfig()
        val draft = ReaderPaletteDraft(original)
        draft.setTextColor(ReaderPaletteMode.DAY, 0xff123456.toInt())
        draft.setBackground(ReaderPaletteMode.NIGHT, 2, "/tmp/paper.png")
        draft.setBackgroundAlpha(65)
        draft.update(ReaderPaletteMode.DAY, ReaderPalette(numberColor = 42))
        assertEquals(100, original.bgAlpha)
        assertEquals(0, original.bgTypeNight)
        var written: ReadStyleConfig? = null
        assertTrue(draft.apply { written = it; Result.success(Unit) }.isSuccess)
        val applied = checkNotNull(written)
        assertEquals(65, applied.bgAlpha)
        assertEquals("/tmp/paper.png", applied.bgStrNight)
        assertEquals(0xff123456.toInt(), applied.textColorForMode(ReaderPaletteMode.DAY))
        assertEquals(42, applied.readerPalette.day.numberColor)
    }

    @Test fun cancellingAppearanceLeavesOriginalUnchanged() {
        val original = ReadStyleConfig()
        val draft = ReaderPaletteDraft(original)
        draft.applyPreset(ReaderPaletteMode.DAY, ReaderPalettePreset.PAPER)
        draft.cancel()
        assertEquals(ReadStyleConfig(), original)
        assertEquals(ReaderPaletteDraftStatus.CANCELLED, draft.state.value.status)
    }

    @Test fun disabledBackgroundKeepsImageAndAllLayerSettingsAcrossSerialization() {
        val original = ReadStyleConfig(bgType = 2, bgStr = "/tmp/paper.png")
        val draft = ReaderPaletteDraft(original)
        val settings = ReaderBackgroundSettings(enabled = false, size = ReaderBackgroundSize.CONTAIN,
            position = ReaderBackgroundPosition.BOTTOM_RIGHT, repeat = true, blend = ReaderBackgroundBlend.MULTIPLY)
        draft.updateBackgroundSettings(ReaderPaletteMode.DAY, settings)
        val config = draft.state.value.snapshot()
        assertEquals("/tmp/paper.png", config.bgStr)
        assertNull(config.backgroundImageForMode(ReaderPaletteMode.DAY))
        val json = io.legado.app.utils.KS_JSON.encodeToString(ReadStyleConfig.serializer(), config)
        val restored = io.legado.app.utils.KS_JSON.decodeFromString(ReadStyleConfig.serializer(), json)
        assertEquals(settings, restored.backgroundSettingsForMode(ReaderPaletteMode.DAY))
        assertTrue(restored.backgroundSettingsForMode(ReaderPaletteMode.NIGHT).enabled)
    }

    @Test fun imageBaseColorDoesNotDiscardImageAndDisabledImageUsesBase() {
        val draft = ReaderPaletteDraft(ReadStyleConfig(bgType = 2, bgStr = "/tmp/paper.png"))
        draft.setBackgroundColor(ReaderPaletteMode.DAY, 0xffaabbcc.toInt())
        var config = draft.state.value.snapshot()
        assertEquals("/tmp/paper.png", config.bgStr)
        assertEquals(2, config.bgType)
        assertEquals(0xffaabbcc.toInt(), config.bgColorForMode(ReaderPaletteMode.DAY))
        draft.updateBackgroundSettings(ReaderPaletteMode.DAY, config.backgroundDay.copy(enabled = false))
        config = draft.state.value.snapshot()
        assertNull(config.backgroundImageForMode(ReaderPaletteMode.DAY))
        assertEquals(0xffaabbcc.toInt(), config.bgColorForMode(ReaderPaletteMode.DAY))
        draft.removeBackgroundImage(ReaderPaletteMode.DAY)
        config = draft.state.value.snapshot()
        assertEquals(0, config.bgType)
        assertEquals(0xffaabbcc.toInt(), config.bgColorForMode(ReaderPaletteMode.DAY))
    }
}
