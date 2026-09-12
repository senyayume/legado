package io.legado.app.ui.book.read.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.model.read.ColorRuleError
import io.legado.app.model.read.ColorRuleRepository
import io.legado.app.model.read.ColorRuleMatcher
import io.legado.app.model.read.ColorRuleTransfer
import io.legado.app.ui.compose.component.*
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.compose.theme.AppTheme
import legado.shared.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ColorRuleScreen(
    state: ColorRuleUiState,
    onEvent: (ColorRuleUiEvent) -> Unit,
    onBack: () -> Unit,
) {
    val editor = state.editor
    Column(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        AppTitleBar(
            title = stringResource(if (editor == null) Res.string.reader_rules_title
                else if (editor.rule.id == 0L) Res.string.reader_rules_new else Res.string.reader_rules_edit),
            onBack = {
                if (!state.busy && !state.needsRefresh) {
                    if (editor == null) onBack() else onEvent(ColorRuleUiEvent.CancelEdit)
                }
            },
            actions = {
                if (editor != null) {
                    RuleIcon(Res.drawable.ic_save, Res.string.action_save, !state.busy && !state.loading) { onEvent(ColorRuleUiEvent.Save) }
                } else {
                    RuleIcon(Res.drawable.ic_add, Res.string.create, !state.busy && !state.needsRefresh) { onEvent(ColorRuleUiEvent.Add) }
                    OverflowMenu { dismiss ->
                        DropdownMenuItem(enabled = !state.busy && !state.needsRefresh, onClick = {
                            dismiss(); onEvent(ColorRuleUiEvent.Import)
                        }) { Text(stringResource(Res.string.reader_rules_import)) }
                        DropdownMenuItem(enabled = !state.busy && !state.needsRefresh && !state.loading && state.rules.isNotEmpty(), onClick = {
                            dismiss(); onEvent(ColorRuleUiEvent.Export)
                        }) { Text(stringResource(Res.string.reader_rules_export)) }
                    }
                }
            },
        )
        if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { error ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(errorResource(error)), modifier = Modifier.weight(1f),
                    color = MaterialTheme.colors.error)
                if (error == ColorRuleError.REFRESH || error == ColorRuleError.STORAGE) {
                    TextButton(onClick = { onEvent(ColorRuleUiEvent.Retry) }, enabled = !state.busy) {
                        Text(stringResource(Res.string.reader_rules_retry))
                    }
                }
                RuleIcon(Res.drawable.ic_baseline_close, Res.string.close, !state.busy && !state.needsRefresh) {
                    onEvent(ColorRuleUiEvent.ClearNotice)
                }
            }
        }
        if (editor != null) {
            RuleEditor(editor, state.bookUrl, !state.busy && !state.loading && !state.needsRefresh, Modifier.weight(1f)) {
                onEvent(ColorRuleUiEvent.Draft(it))
            }
        } else {
            state.importStats?.let {
                Text(stringResource(Res.string.reader_rules_import_done, it.inserted, it.updated, it.skipped),
                    modifier = Modifier.padding(16.dp), color = AppTheme.colors.primaryText)
            }
            if (state.exported) Text(stringResource(Res.string.reader_rules_export_done),
                modifier = Modifier.padding(16.dp), color = AppTheme.colors.primaryText)
            if (!state.loading && state.rules.isEmpty() && state.error == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.reader_rules_empty), color = AppTheme.colors.secondaryText)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.rules, key = { it.id }) { rule ->
                        val siblings = state.rules.filter { it.bookUrl == rule.bookUrl }
                        val index = siblings.indexOf(rule)
                        RuleRow(rule, !state.busy && !state.needsRefresh, index > 0, index < siblings.lastIndex, onEvent)
                        Divider(color = AppTheme.colors.controlNormal.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
    state.pendingDelete?.let { rule ->
        AppAlertDialog(
            onDismissRequest = { if (!state.busy && !state.needsRefresh) onEvent(ColorRuleUiEvent.CancelDelete) },
            title = stringResource(Res.string.delete),
            message = stringResource(Res.string.sure_del) + "\n" + ruleLabel(rule) +
                (state.error?.let { "\n" + stringResource(errorResource(it)) } ?: ""),
            okButton = AlertButton(stringResource(Res.string.yes), dismissOnClick = false, enabled = !state.busy) {
                onEvent(ColorRuleUiEvent.Delete)
            },
            cancelButton = AlertButton(stringResource(Res.string.no), enabled = !state.busy && !state.needsRefresh) {
                onEvent(ColorRuleUiEvent.CancelDelete)
            },
        )
    }
    state.importPreview?.let { preview ->
        AppAlertDialog(
            onDismissRequest = { if (!state.busy && !state.needsRefresh) onEvent(ColorRuleUiEvent.CancelImport) },
            title = stringResource(Res.string.reader_rules_import_preview),
            okButton = AlertButton(stringResource(Res.string.ok), dismissOnClick = false,
                enabled = !state.busy && preview.rules.isNotEmpty()) { onEvent(ColorRuleUiEvent.ApplyImport) },
            cancelButton = AlertButton(stringResource(Res.string.cancel), enabled = !state.busy && !state.needsRefresh) {
                onEvent(ColorRuleUiEvent.CancelImport)
            },
            content = {
                Column {
                    state.error?.let {
                        Text(stringResource(errorResource(it)), color = MaterialTheme.colors.error)
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.reader_rules_import_counts, preview.rules.size, preview.skippedCount),
                        color = AppTheme.colors.primaryText)
                    RuleSwitch(stringResource(Res.string.reader_rules_overwrite), state.overwriteColors, !state.busy && !state.needsRefresh) {
                        onEvent(ColorRuleUiEvent.OverwriteColors(it))
                    }
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(preview.errors) { issue ->
                            Text(stringResource(Res.string.reader_rules_import_issue, issue.index,
                                stringResource(errorResource(issue.reason))),
                                color = MaterialTheme.colors.error, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        items(preview.rules) { rule ->
                            Text(ruleLabel(rule), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = AppTheme.colors.primaryText, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: ReadColorRule, enabled: Boolean, canMoveUp: Boolean, canMoveDown: Boolean,
    onEvent: (ColorRuleUiEvent) -> Unit,
) {
    Row(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }
        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(enabled) { onEvent(ColorRuleUiEvent.Edit(rule)) }) {
            Text(ruleLabel(rule), maxLines = 2, overflow = TextOverflow.Ellipsis, color = AppTheme.colors.primaryText)
            Text(stringResource(typeResource(rule.ruleType)) + " · " +
                stringResource(if (rule.bookUrl.isEmpty()) Res.string.reader_rules_global else Res.string.reader_rules_book),
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = AppTheme.colors.secondaryText)
        }
        AppSwitch(rule.enabled, { onEvent(ColorRuleUiEvent.Enable(rule.id, it)) }, enabled = enabled)
        RuleIcon(Res.drawable.ic_edit, Res.string.edit, enabled) { onEvent(ColorRuleUiEvent.Edit(rule)) }
        OverflowMenu { dismiss ->
            DropdownMenuItem(enabled = enabled && canMoveUp, onClick = {
                dismiss(); onEvent(ColorRuleUiEvent.Move(rule.id, -1))
            }) { Text(stringResource(Res.string.reader_rules_move_up)) }
            DropdownMenuItem(enabled = enabled && canMoveDown, onClick = {
                dismiss(); onEvent(ColorRuleUiEvent.Move(rule.id, 1))
            }) { Text(stringResource(Res.string.reader_rules_move_down)) }
            DropdownMenuItem(enabled = enabled, onClick = {
                dismiss(); onEvent(ColorRuleUiEvent.AskDelete(rule))
            }) { Text(stringResource(Res.string.delete)) }
        }
    }
}

@Composable
private fun RuleEditor(
    draft: ColorRuleDraft, bookUrl: String, enabled: Boolean, modifier: Modifier,
    onChange: (ColorRuleDraft) -> Unit,
) {
    val rule = draft.rule
    fun change(value: ReadColorRule) = onChange(draft.copy(rule = value))
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            RuleChoice(stringResource(Res.string.reader_rules_type), rule.ruleType,
                listOf(ReadColorRule.TYPE_KEYWORD, ReadColorRule.TYPE_PAIR, ReadColorRule.TYPE_REGEX),
                { stringResource(typeResource(it)) }, enabled) { change(rule.copy(ruleType = it)) }
        }
        when (rule.ruleType) {
            ReadColorRule.TYPE_PAIR -> {
                item {
                    AppTextField(rule.pairLeft, { if (it.length <= ColorRuleRepository.MAX_KEYWORD_LENGTH) change(rule.copy(pairLeft = it)) },
                        label = stringResource(Res.string.reader_rules_left), enabled = enabled, singleLine = true)
                }
                item {
                    AppTextField(rule.pairRight, { if (it.length <= ColorRuleRepository.MAX_KEYWORD_LENGTH) change(rule.copy(pairRight = it)) },
                        label = stringResource(Res.string.reader_rules_right), enabled = enabled, singleLine = true)
                }
            }
            ReadColorRule.TYPE_REGEX -> item {
                AppTextField(rule.pattern, { if (it.length <= ColorRuleMatcher.MAX_REGEX_LENGTH) change(rule.copy(pattern = it)) },
                    label = stringResource(Res.string.reader_rules_pattern), enabled = enabled, maxLines = 4)
            }
            else -> item {
                AppTextField(rule.keyword, { if (it.length <= ColorRuleRepository.MAX_KEYWORD_LENGTH) change(rule.copy(keyword = it)) },
                    label = stringResource(Res.string.reader_rules_keyword), enabled = enabled, maxLines = 3)
            }
        }
        item {
            RuleChoice(stringResource(Res.string.reader_rules_scope), rule.bookUrl.isNotEmpty(),
                if (bookUrl.isBlank()) listOf(false) else listOf(false, true),
                { stringResource(if (it) Res.string.reader_rules_book else Res.string.reader_rules_global) }, enabled) {
                change(rule.copy(bookUrl = if (it) bookUrl else ""))
            }
            RuleSwitch(stringResource(Res.string.reader_rules_enabled), rule.enabled, enabled) { change(rule.copy(enabled = it)) }
            RuleSwitch(stringResource(Res.string.reader_rules_case), rule.caseSensitive, enabled) { change(rule.copy(caseSensitive = it)) }
        }
        item { Text(stringResource(Res.string.reader_rules_style), color = AppTheme.colors.accent) }
        item {
            RuleColor(stringResource(Res.string.reader_rules_foreground), rule.foregroundColor, enabled) { change(rule.copy(foregroundColor = it)) }
            RuleColor(stringResource(Res.string.reader_rules_background), rule.backgroundColor, enabled) { change(rule.copy(backgroundColor = it)) }
            RuleBoolean(stringResource(Res.string.reader_rules_underline), rule.underline, enabled) { change(rule.copy(underline = it)) }
            RuleBoolean(stringResource(Res.string.reader_rules_bold), rule.bold, enabled) { change(rule.copy(bold = it)) }
        }
        if (rule.ruleType == ReadColorRule.TYPE_PAIR) {
            item {
                RuleSwitch(stringResource(Res.string.reader_rules_content_enabled), rule.contentEnabled, enabled) { change(rule.copy(contentEnabled = it)) }
            }
            if (rule.contentEnabled) {
                item { Text(stringResource(Res.string.reader_rules_content_style), color = AppTheme.colors.accent) }
                item {
                    RuleColor(stringResource(Res.string.reader_rules_foreground), rule.contentForegroundColor, enabled) { change(rule.copy(contentForegroundColor = it)) }
                    RuleColor(stringResource(Res.string.reader_rules_background), rule.contentBackgroundColor, enabled) { change(rule.copy(contentBackgroundColor = it)) }
                    RuleBoolean(stringResource(Res.string.reader_rules_underline), rule.contentUnderline, enabled) { change(rule.copy(contentUnderline = it)) }
                    RuleBoolean(stringResource(Res.string.reader_rules_bold), rule.contentBold, enabled) { change(rule.copy(contentBold = it)) }
                }
            }
        }
        item {
            AppTextField(draft.chapterStart, { if (it.length <= 10) onChange(draft.copy(chapterStart = it)) },
                label = stringResource(Res.string.reader_rules_start), enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        }
        item {
            AppTextField(draft.chapterEnd, { if (it.length <= 10) onChange(draft.copy(chapterEnd = it)) },
                label = stringResource(Res.string.reader_rules_end), enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        }
        item {
            AppTextField(rule.excludedChapterRanges, {
                if (it.length <= ColorRuleTransfer.MAX_RANGE_JSON_LENGTH) change(rule.copy(excludedChapterRanges = it))
            }, label = stringResource(Res.string.reader_rules_excluded), enabled = enabled, maxLines = 4)
        }
    }
}

@Composable
private fun RuleSwitch(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = AppTheme.colors.primaryText)
        AppSwitch(value, onChange, enabled = enabled)
    }
}

@Composable
private fun <T> RuleChoice(
    label: String, value: T, values: List<T>, text: @Composable (T) -> String,
    enabled: Boolean, onChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = AppTheme.colors.secondaryText)
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled) { expanded = true },
                verticalAlignment = Alignment.CenterVertically) {
                Text(text(value), Modifier.weight(1f), color = AppTheme.colors.primaryText)
                Icon(painterResource(Res.drawable.ic_arrow_down), contentDescription = label,
                    tint = AppTheme.colors.primaryText, modifier = Modifier.size(24.dp))
            }
            AppDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                values.forEach { option ->
                    DropdownMenuItem(onClick = { expanded = false; onChange(option) }) { Text(text(option)) }
                }
            }
        }
    }
}

@Composable
private fun RuleBoolean(label: String, value: Boolean?, enabled: Boolean, onChange: (Boolean?) -> Unit) {
    RuleChoice(label, value, listOf(null, true, false), {
        stringResource(when (it) {
            null -> Res.string.reader_rules_inherit
            true -> Res.string.reader_rules_enabled
            false -> Res.string.reader_rules_disabled
        })
    }, enabled, onChange)
}

@Composable
private fun RuleColor(label: String, value: Int?, enabled: Boolean, onChange: (Int?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = AppTheme.colors.primaryText)
        if (value == null) {
            TextButton(onClick = { picking = true }, enabled = enabled) {
                Text(stringResource(Res.string.reader_rules_inherit))
            }
        } else {
            Box(Modifier.size(36.dp).background(Color(value))
                .border(1.dp, AppTheme.colors.controlNormal)
                .semantics { contentDescription = label }.clickable(enabled) { picking = true })
            RuleIcon(Res.drawable.ic_baseline_close, Res.string.reader_rules_inherit, enabled) { onChange(null) }
        }
    }
    if (picking) {
        ColorPickerDialog(value ?: 0xFF000000.toInt(), label,
            onDismissRequest = { picking = false },
            onConfirm = { picking = false; onChange(it) }, showAlphaSlider = true)
    }
}

@Composable
private fun RuleIcon(icon: DrawableResource, label: StringResource, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), stringResource(label),
            tint = if (enabled) AppTheme.colors.primaryText else AppTheme.colors.textDisabled,
            modifier = Modifier.size(24.dp))
    }
}

private fun ruleLabel(rule: ReadColorRule): String = when (rule.ruleType) {
    ReadColorRule.TYPE_PAIR -> rule.pairLeft + " … " + rule.pairRight
    ReadColorRule.TYPE_REGEX -> rule.pattern
    else -> rule.keyword
}

private fun typeResource(type: String): StringResource = when (type) {
    ReadColorRule.TYPE_PAIR -> Res.string.reader_rules_pair
    ReadColorRule.TYPE_REGEX -> Res.string.reader_rules_regex
    ReadColorRule.TYPE_PRESET -> Res.string.reader_rules_preset
    else -> Res.string.reader_rules_keyword
}

private fun errorResource(error: ColorRuleError): StringResource = when (error) {
    ColorRuleError.INVALID_RULE -> Res.string.reader_rules_error_invalid
    ColorRuleError.INVALID_RANGE -> Res.string.reader_rules_error_range
    ColorRuleError.INVALID_ORDER -> Res.string.reader_rules_error_order
    ColorRuleError.NOT_FOUND -> Res.string.reader_rules_error_missing
    ColorRuleError.STORAGE -> Res.string.reader_rules_error_storage
    ColorRuleError.INPUT_TOO_LARGE -> Res.string.reader_rules_error_size
    ColorRuleError.UNSUPPORTED_VERSION -> Res.string.reader_rules_error_version
    ColorRuleError.INVALID_FILE -> Res.string.reader_rules_error_file
    ColorRuleError.FILE_IO -> Res.string.reader_rules_error_io
    ColorRuleError.PLATFORM_UNAVAILABLE -> Res.string.reader_rules_error_platform
    ColorRuleError.REFRESH -> Res.string.reader_rules_error_refresh
    ColorRuleError.DUPLICATE -> Res.string.reader_rules_error_duplicate
}
