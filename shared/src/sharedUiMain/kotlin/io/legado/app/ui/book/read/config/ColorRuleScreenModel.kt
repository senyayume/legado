package io.legado.app.ui.book.read.config

import io.legado.app.data.entities.ReadColorRule
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.model.read.*
import io.legado.app.ui.root.FileFilter
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.root.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

data class ColorRuleDraft(
    val rule: ReadColorRule,
    val chapterStart: String = rule.chapterStart?.toString().orEmpty(),
    val chapterEnd: String = rule.chapterEnd?.toString().orEmpty(),
)

data class ColorRuleUiState(
    val bookUrl: String,
    val rules: List<ReadColorRule> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val needsRefresh: Boolean = false,
    val editor: ColorRuleDraft? = null,
    val pendingDelete: ReadColorRule? = null,
    val importPreview: ColorRuleImportReport? = null,
    val overwriteColors: Boolean = false,
    val importStats: ColorRuleImportStats? = null,
    val exported: Boolean = false,
    val error: ColorRuleError? = null,
)

sealed interface ColorRuleUiEvent {
    data object Add : ColorRuleUiEvent
    data class Edit(val rule: ReadColorRule) : ColorRuleUiEvent
    data class Draft(val value: ColorRuleDraft) : ColorRuleUiEvent
    data object Save : ColorRuleUiEvent
    data object CancelEdit : ColorRuleUiEvent
    data class Enable(val id: Long, val enabled: Boolean) : ColorRuleUiEvent
    data class Move(val id: Long, val direction: Int) : ColorRuleUiEvent
    data class AskDelete(val rule: ReadColorRule) : ColorRuleUiEvent
    data object Delete : ColorRuleUiEvent
    data object CancelDelete : ColorRuleUiEvent
    data object Import : ColorRuleUiEvent
    data object Export : ColorRuleUiEvent
    data class OverwriteColors(val enabled: Boolean) : ColorRuleUiEvent
    data object ApplyImport : ColorRuleUiEvent
    data object CancelImport : ColorRuleUiEvent
    data object ClearNotice : ColorRuleUiEvent
    data object Retry : ColorRuleUiEvent
}

class ColorRuleScreenModel(
    private val scope: CoroutineScope,
    private val bookUrl: String,
    initialRule: ReadColorRule? = null,
    private val repository: ColorRuleRepository = ColorRuleRepository(),
    private val exportFile: suspend (String) -> Result<Boolean> = {
        Result.failure(ColorRuleException(ColorRuleError.PLATFORM_UNAVAILABLE))
    },
    private val onRulesChanged: suspend () -> Result<Unit>,
) : ScreenModel {
    private val mutableState = MutableStateFlow(ColorRuleUiState(
        bookUrl = bookUrl, editor = initialRule?.let(::ColorRuleDraft),
    ))
    val state = mutableState.asStateFlow()
    private var observation: kotlinx.coroutines.Job? = null
    private var commandJob: kotlinx.coroutines.Job? = null
    private var initialEditor = initialRule
    private var pendingCompletion: (() -> Unit)? = null

    init { observe() }

    override fun onCleared() {
        observation?.cancel()
        commandJob?.cancel()
        pendingCompletion = null
    }

    private fun observe() {
        observation?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        observation = scope.launch {
            repository.flowForBook(bookUrl)
                .flowOn(IoDispatcher)
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    mutableState.update { it.copy(loading = false, error = ColorRuleError.STORAGE) }
                }.collect { rules ->
                    val initial = initialEditor
                    initialEditor = null
                    mutableState.update {
                        it.copy(rules = rules, loading = false,
                            editor = if (initial == null) it.editor
                            else ColorRuleDraft(ColorRuleRepository.prepareKeywordRule(initial, rules)))
                    }
                }
        }
    }

    fun dispatch(event: ColorRuleUiEvent) {
        if (mutableState.value.busy) return
        if (mutableState.value.needsRefresh) {
            when (event) {
                ColorRuleUiEvent.Retry, ColorRuleUiEvent.Save, ColorRuleUiEvent.Delete,
                ColorRuleUiEvent.ApplyImport -> command { finishRefresh() }
                else -> Unit
            }
            return
        }
        when (event) {
            ColorRuleUiEvent.Add -> mutableState.update {
                it.copy(editor = ColorRuleDraft(ReadColorRule(bookUrl = bookUrl)), error = null)
            }
            is ColorRuleUiEvent.Edit -> mutableState.update {
                it.copy(editor = ColorRuleDraft(event.rule), error = null)
            }
            is ColorRuleUiEvent.Draft -> mutableState.update { it.copy(editor = event.value, error = null) }
            ColorRuleUiEvent.CancelEdit -> {
                initialEditor = null
                mutableState.update { it.copy(editor = null, error = null) }
            }
            ColorRuleUiEvent.Save -> save()
            is ColorRuleUiEvent.Enable -> command {
                repository.setEnabled(event.id, event.enabled).getOrThrow()
                refreshAfterChange()
            }
            is ColorRuleUiEvent.Move -> command {
                repository.move(event.id, event.direction).getOrThrow()
                refreshAfterChange()
            }
            is ColorRuleUiEvent.AskDelete -> mutableState.update { it.copy(pendingDelete = event.rule) }
            ColorRuleUiEvent.CancelDelete -> mutableState.update { it.copy(pendingDelete = null) }
            ColorRuleUiEvent.Delete -> {
                val rule = mutableState.value.pendingDelete ?: return
                command {
                    repository.delete(rule.id).getOrThrow()
                    refreshAfterChange { mutableState.update { it.copy(pendingDelete = null) } }
                }
            }
            ColorRuleUiEvent.Import -> importFile()
            ColorRuleUiEvent.Export -> command {
                val rules = repository.getForBook(bookUrl).getOrThrow()
                val text = ColorRuleTransfer.encode(rules).getOrThrow()
                val saved = exportFile(text).getOrThrow()
                mutableState.update { it.copy(exported = saved) }
            }
            is ColorRuleUiEvent.OverwriteColors -> mutableState.update { it.copy(overwriteColors = event.enabled) }
            ColorRuleUiEvent.CancelImport -> mutableState.update { it.copy(importPreview = null) }
            ColorRuleUiEvent.ApplyImport -> {
                val preview = mutableState.value.importPreview ?: return
                val overwrite = mutableState.value.overwriteColors
                command {
                    val stats = repository.applyImport(preview.rules, bookUrl, overwrite).getOrThrow()
                    refreshAfterChange {
                        mutableState.update { it.copy(importPreview = null, importStats = stats) }
                    }
                }
            }
            ColorRuleUiEvent.ClearNotice -> mutableState.update {
                it.copy(error = null, exported = false, importStats = null)
            }
            ColorRuleUiEvent.Retry -> observe()
        }
    }

    private fun save() {
        if (mutableState.value.loading || initialEditor != null) return
        val draft = mutableState.value.editor ?: return
        command {
            fun chapter(value: String): Int? {
                if (value.isBlank()) return null
                return value.toIntOrNull() ?: throw ColorRuleException(ColorRuleError.INVALID_RANGE)
            }
            val saved = repository.save(draft.rule.copy(
                chapterStart = chapter(draft.chapterStart),
                chapterEnd = chapter(draft.chapterEnd),
            )).getOrThrow()
            mutableState.update { it.copy(editor = ColorRuleDraft(saved)) }
            refreshAfterChange { mutableState.update { it.copy(editor = null) } }
        }
    }

    private suspend fun refreshAfterChange(onSuccess: () -> Unit = {}) {
        pendingCompletion = onSuccess
        mutableState.update { it.copy(needsRefresh = true) }
        finishRefresh()
    }

    private suspend fun finishRefresh() {
        try {
            onRulesChanged().getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ColorRuleException(ColorRuleError.REFRESH, e)
        }
        pendingCompletion?.invoke()
        pendingCompletion = null
        mutableState.update { it.copy(needsRefresh = false) }
    }

    private fun importFile() = command {
        val files = PlatformServiceProviders.getOrNull()?.files
            ?: throw ColorRuleException(ColorRuleError.PLATFORM_UNAVAILABLE)
        val path = files.pickFile(FileFilter(extensions = listOf("json")))
            ?: return@command
        val text = try {
            FileSystem.SYSTEM.source(path.toPath()).buffer().use { source ->
                if (source.request(ColorRuleTransfer.MAX_IMPORT_BYTES.toLong() + 1)) {
                    throw ColorRuleException(ColorRuleError.INPUT_TOO_LARGE)
                }
                source.readUtf8()
            }
        } catch (e: ColorRuleException) {
            throw e
        } catch (e: Exception) {
            throw ColorRuleException(ColorRuleError.FILE_IO, e)
        }
        val preview = ColorRuleTransfer.decode(text, bookUrl).getOrThrow()
        mutableState.update { it.copy(importPreview = preview, overwriteColors = false) }
    }

    private fun command(block: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true, error = null, importStats = null, exported = false) }
        commandJob = scope.launch(IoDispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(error = (e as? ColorRuleException)?.reason ?: ColorRuleError.STORAGE)
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
