package io.legado.app.ui.book.read.config

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Slider
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.model.read.ReaderPalette
import io.legado.app.model.read.ReaderPaletteColorPolicy
import io.legado.app.model.read.ReaderPaletteDraft
import io.legado.app.model.read.ReaderPaletteDraftStatus
import io.legado.app.model.read.ReaderPaletteMode
import io.legado.app.model.read.ReaderPalettePreview
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.model.read.ReaderPalettePreset
import io.legado.app.model.read.ReaderBackgroundSettings
import io.legado.app.model.read.ReaderBackgroundSize
import io.legado.app.model.read.ReaderBackgroundPosition
import io.legado.app.model.read.ReaderBackgroundBlend
import io.legado.app.model.read.FontColorGenerator
import io.legado.app.ui.book.read.page.ReaderBackgroundImageCache
import io.legado.app.ui.book.read.page.drawReaderBackgroundBitmap
import io.legado.app.model.read.ReaderPaletteTab
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialogContent
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDialog
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.RadioChip
import io.legado.app.ui.compose.component.appDialogSize
import io.legado.app.ui.compose.preference.ColorPickerDialog
import io.legado.app.ui.compose.theme.AppTheme
import kotlin.reflect.KMutableProperty1
import kotlinx.coroutines.launch
import legado.shared.generated.resources.reader_palette_preset_light
import legado.shared.generated.resources.reader_palette_preset_dark
import legado.shared.generated.resources.reader_palette_body
import legado.shared.generated.resources.reader_palette_random
import legado.shared.generated.resources.reader_palette_background
import legado.shared.generated.resources.reader_palette_background_images
import legado.shared.generated.resources.reader_palette_import_image
import legado.shared.generated.resources.reader_palette_no_images
import legado.shared.generated.resources.reader_palette_opacity
import legado.shared.generated.resources.reader_palette_image_enabled
import legado.shared.generated.resources.reader_palette_image_repeat
import legado.shared.generated.resources.reader_palette_size_cover
import legado.shared.generated.resources.reader_palette_size_contain
import legado.shared.generated.resources.reader_palette_size_original
import legado.shared.generated.resources.reader_palette_position_top_left
import legado.shared.generated.resources.reader_palette_position_top_center
import legado.shared.generated.resources.reader_palette_position_top_right
import legado.shared.generated.resources.reader_palette_position_center_left
import legado.shared.generated.resources.reader_palette_position_center
import legado.shared.generated.resources.reader_palette_position_center_right
import legado.shared.generated.resources.reader_palette_position_bottom_left
import legado.shared.generated.resources.reader_palette_position_bottom_center
import legado.shared.generated.resources.reader_palette_position_bottom_right
import legado.shared.generated.resources.reader_palette_blend_normal
import legado.shared.generated.resources.reader_palette_blend_multiply
import legado.shared.generated.resources.reader_palette_blend_lighten
import legado.shared.generated.resources.reader_palette_blend_overlay
import legado.shared.generated.resources.reader_palette_blend_soft_light
import legado.shared.generated.resources.reader_palette_blend_screen
import legado.shared.generated.resources.reader_palette_blend_darken
import legado.shared.generated.resources.reader_palette_importing
import legado.shared.generated.resources.reader_palette_remove_image
import legado.shared.generated.resources.reader_palette_image_failed
import legado.shared.generated.resources.reader_palette_image_loading
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.reader_palette_annotation
import legado.shared.generated.resources.reader_palette_annotation_background
import legado.shared.generated.resources.reader_palette_apply
import legado.shared.generated.resources.reader_palette_applying
import legado.shared.generated.resources.reader_palette_bookmark
import legado.shared.generated.resources.reader_palette_bookmark_background
import legado.shared.generated.resources.reader_palette_bracket_content
import legado.shared.generated.resources.reader_palette_bracket_enabled
import legado.shared.generated.resources.reader_palette_bracket_symbol
import legado.shared.generated.resources.reader_palette_chapter
import legado.shared.generated.resources.reader_palette_day
import legado.shared.generated.resources.reader_palette_eink
import legado.shared.generated.resources.reader_palette_failure
import legado.shared.generated.resources.reader_palette_highlight
import legado.shared.generated.resources.reader_palette_inherit
import legado.shared.generated.resources.reader_palette_letter
import legado.shared.generated.resources.reader_palette_night
import legado.shared.generated.resources.reader_palette_number
import legado.shared.generated.resources.reader_palette_punctuation
import legado.shared.generated.resources.reader_palette_quote_content
import legado.shared.generated.resources.reader_palette_quote_symbol
import legado.shared.generated.resources.reader_palette_reader
import legado.shared.generated.resources.reader_palette_reset
import legado.shared.generated.resources.reader_palette_sample
import legado.shared.generated.resources.reader_palette_sample_annotation
import legado.shared.generated.resources.reader_palette_sample_bookmark
import legado.shared.generated.resources.reader_palette_sample_search
import legado.shared.generated.resources.reader_palette_sample_title
import legado.shared.generated.resources.reader_palette_search
import legado.shared.generated.resources.reader_palette_search_background
import legado.shared.generated.resources.reader_palette_special
import legado.shared.generated.resources.reader_palette_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Preview projection from the isolated appearance draft. */
data class ReaderPaletteBaseColors(val text: Int, val background: Int)

/**
 * Keep [draft] stable for one opening. [onApply] persists the complete palette set through
 * the configuration owner and returns only after the reader has been refreshed.
 */
@Composable
fun ReaderPaletteDialog(
    draft: ReaderPaletteDraft,
    initialMode: ReaderPaletteMode,
    onApply: suspend (ReadStyleConfig) -> Result<Unit>,
    onDismiss: () -> Unit,
    backgroundImages: List<BgImageItem> = emptyList(),
    onChooseBackground: ((ReaderPaletteMode) -> Unit)? = null,
    isImporting: Boolean = false
) {
    val dismiss = {
        if (!isImporting) {
            draft.cancel()
            if (draft.state.value.status == ReaderPaletteDraftStatus.CANCELLED) onDismiss()
        }
    }
    AppDialog(onDismissRequest = dismiss, properties = AppDialogSizes.properties()) {
        ReaderPaletteScreen(
            draft, initialMode, onApply, onDismiss, backgroundImages, onChooseBackground,
            isImporting = isImporting,
            modifier = Modifier.appDialogSize()
        )
    }
}

/** Dialog content, also suitable for an existing platform dialog host. */
@Composable
fun ReaderPaletteScreen(
    draft: ReaderPaletteDraft,
    initialMode: ReaderPaletteMode,
    onApply: suspend (ReadStyleConfig) -> Result<Unit>,
    onDismiss: () -> Unit,
    backgroundImages: List<BgImageItem> = emptyList(),
    onChooseBackground: ((ReaderPaletteMode) -> Unit)? = null,
    isImporting: Boolean = false,
    modifier: Modifier = Modifier
) {
    val state by draft.state.collectAsState()
    var mode by remember(draft) { mutableStateOf(initialMode) }
    var tab by remember(draft) { mutableStateOf(ReaderPaletteTab.READER) }
    var picking by remember(draft) { mutableStateOf<PaletteField?>(null) }
    val scope = rememberCoroutineScope()
    val editable = state.status == ReaderPaletteDraftStatus.EDITING && !isImporting
    val palette = state.palette(mode)
    val config = state.snapshot()
    val base = ReaderPaletteBaseColors(config.textColorForMode(mode), config.bgColorForMode(mode))
    var basePicker by remember(draft) { mutableStateOf<String?>(null) }
    var showBackgrounds by remember(draft) { mutableStateOf(false) }
    val dismiss = {
        if (!isImporting) {
            draft.cancel()
            if (draft.state.value.status == ReaderPaletteDraftStatus.CANCELLED) onDismiss()
        }
    }
    AppAlertDialogContent(
        modifier = modifier,
        onDismissRequest = dismiss,
        title = stringResource(Res.string.reader_palette_title),
        neutralButton = AlertButton(
            text = stringResource(Res.string.reader_palette_reset),
            dismissOnClick = false,
            enabled = editable,
            onClick = { draft.reset(mode, tab) }
        ),
        cancelButton = AlertButton(
            text = stringResource(Res.string.cancel),
            dismissOnClick = false,
            enabled = editable,
            onClick = dismiss
        ),
        okButton = AlertButton(
            text = stringResource(
                if (state.status == ReaderPaletteDraftStatus.APPLYING)
                    Res.string.reader_palette_applying else Res.string.reader_palette_apply
            ),
            dismissOnClick = false,
            enabled = editable,
            onClick = {
                scope.launch {
                    if (draft.apply(onApply).isSuccess) onDismiss()
                }
            }
        )
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderPaletteMode.entries.forEach { candidate ->
                    RadioChip(
                        text = stringResource(when (candidate) {
                            ReaderPaletteMode.DAY -> Res.string.reader_palette_day
                            ReaderPaletteMode.NIGHT -> Res.string.reader_palette_night
                            ReaderPaletteMode.EINK -> Res.string.reader_palette_eink
                        }),
                        checked = mode == candidate,
                        modifier = Modifier.weight(1f),
                        onClick = { mode = candidate }
                    )
                }
            }
            TabRow(
                selectedTabIndex = tab.ordinal,
                backgroundColor = AppTheme.colors.fillet,
                contentColor = AppTheme.colors.accent
            ) {
                ReaderPaletteTab.entries.forEach { candidate ->
                    Tab(
                        selected = tab == candidate,
                        onClick = { tab = candidate },
                        text = { Text(stringResource(
                            if (candidate == ReaderPaletteTab.READER)
                                Res.string.reader_palette_reader else Res.string.reader_palette_highlight
                        )) }
                    )
                }
            }
            PalettePreview(palette, base, tab, config.backgroundImageForMode(mode), config.bgAlpha / 100f, config.backgroundSettingsForMode(mode))
            if (isImporting) Text(stringResource(Res.string.reader_palette_importing))
            if (state.failure != null) {
                Text(
                    stringResource(Res.string.reader_palette_failure),
                    color = AppTheme.colors.primaryText,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (tab == ReaderPaletteTab.READER) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ReaderPalettePreset.entries.forEach { preset ->
                        TextButton(enabled = editable, onClick = { draft.applyPreset(mode, preset) }) {
                            Text(stringResource(if (preset == ReaderPalettePreset.PAPER)
                                Res.string.reader_palette_preset_light else Res.string.reader_palette_preset_dark))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = editable, onClick = { basePicker = "body" }) {
                        Text(stringResource(Res.string.reader_palette_body))
                    }
                    TextButton(enabled = editable, onClick = { draft.randomizeTextColor(mode) }) {
                        Text(stringResource(Res.string.reader_palette_random))
                    }
                    TextButton(enabled = editable, onClick = { basePicker = "background" }) {
                        Text(stringResource(Res.string.reader_palette_background))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = editable, onClick = { showBackgrounds = !showBackgrounds }) {
                        Text(stringResource(Res.string.reader_palette_background_images))
                    }
                    TextButton(enabled = editable && onChooseBackground != null, onClick = { onChooseBackground?.invoke(mode) }) {
                        Text(stringResource(Res.string.reader_palette_import_image))
                    }
                }
                if (showBackgrounds) {
                    backgroundImages.forEach { item ->
                        TextButton(enabled = editable, onClick = { draft.setBackground(mode, 1, item.fileName) }) {
                            Text(item.label)
                        }
                    }
                    if (backgroundImages.isEmpty()) Text(stringResource(Res.string.reader_palette_no_images))
                }
                if (config.backgroundTypeForMode(mode) != 0) {
                    Text(stringResource(Res.string.reader_palette_opacity, config.bgAlpha))
                    Slider(
                        value = config.bgAlpha.toFloat(), onValueChange = { draft.setBackgroundAlpha(it.toInt()) },
                        valueRange = 0f..100f, steps = 19, enabled = editable
                    )
                    TextButton(enabled = editable, onClick = { draft.removeBackgroundImage(mode) }) {
                        Text(stringResource(Res.string.reader_palette_remove_image))
                    }
                    BackgroundOptions(config.backgroundSettingsForMode(mode), editable) {
                        draft.updateBackgroundSettings(mode, it)
                    }
                }
                val bracketEnabledLabel = stringResource(Res.string.reader_palette_bracket_enabled)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        bracketEnabledLabel,
                        color = AppTheme.colors.primaryText,
                        modifier = Modifier.weight(1f)
                    )
                    AppCheckbox(
                        checked = palette.bracketPairsEnabled,
                        modifier = Modifier.semantics { contentDescription = bracketEnabledLabel },
                        enabled = editable,
                        onCheckedChange = {
                            draft.update(mode, palette.copy(bracketPairsEnabled = it))
                        }
                    )
                }
            }
            paletteFields.filter { it.tab == tab }.forEach { field ->
                val label = stringResource(field.label)
                val value = field.property.get(palette)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(label, color = AppTheme.colors.primaryText, modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = editable && !ReaderPaletteColorPolicy.preservesAlpha(field.key),
                        onClick = {
                            val changed = state.palette(mode)
                            field.property.set(changed, FontColorGenerator.next(value, background = base.background))
                            draft.update(mode, changed)
                        }
                    ) { Text(stringResource(Res.string.reader_palette_random)) }
                    TextButton(
                        enabled = editable && value != null,
                        onClick = {
                            val changed = state.palette(mode)
                            field.property.set(changed, null)
                            draft.update(mode, changed)
                        }
                    ) {
                        Text(
                            stringResource(Res.string.reader_palette_inherit),
                            color = AppTheme.colors.secondaryText
                        )
                    }
                    Box(
                        Modifier.size(48.dp)
                            .semantics { contentDescription = label }
                            .clickable(enabled = editable, role = Role.Button) { picking = field }
                            .padding(8.dp)
                            .background(Color(value ?: (
                                if (ReaderPaletteColorPolicy.preservesAlpha(field.key))
                                    base.background else base.text
                            )), CircleShape)
                            .border(1.dp, AppTheme.colors.secondaryText, CircleShape)
                    )
                }
            }
        }
    }
    picking?.let { field ->
        ColorPickerDialog(
            initColor = field.property.get(palette) ?: (
                if (ReaderPaletteColorPolicy.preservesAlpha(field.key)) base.background else base.text
            ),
            title = stringResource(field.label),
            showAlphaSlider = ReaderPaletteColorPolicy.preservesAlpha(field.key),
            onDismissRequest = { picking = null },
            onConfirm = { selected ->
                val changed = draft.state.value.palette(mode)
                field.property.set(changed, ReaderPaletteColorPolicy.normalize(field.key, selected))
                draft.update(mode, changed)
                picking = null
            }
        )
    }
    basePicker?.let { key ->
        ColorPickerDialog(
            initColor = if (key == "body") base.text else base.background,
            title = stringResource(if (key == "body") Res.string.reader_palette_body else Res.string.reader_palette_background),
            showAlphaSlider = false,
            onDismissRequest = { basePicker = null },
            onConfirm = { color ->
                if (key == "body") draft.setTextColor(mode, color)
                else draft.setBackgroundColor(mode, color)
                basePicker = null
            }
        )
    }
}

@Composable
private fun BackgroundOptions(settings: ReaderBackgroundSettings, editable: Boolean, onChange: (ReaderBackgroundSettings) -> Unit) {
    val enabledLabel = stringResource(Res.string.reader_palette_image_enabled)
    val repeatLabel = stringResource(Res.string.reader_palette_image_repeat)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(enabledLabel, modifier = Modifier.weight(1f))
        AppCheckbox(checked = settings.enabled, enabled = editable,
            modifier = Modifier.semantics { contentDescription = enabledLabel },
            onCheckedChange = { onChange(settings.copy(enabled = it)) })
        Text(repeatLabel)
        AppCheckbox(checked = settings.repeat, enabled = editable,
            modifier = Modifier.semantics { contentDescription = repeatLabel },
            onCheckedChange = { onChange(settings.copy(repeat = it)) })
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        ReaderBackgroundSize.entries.forEach { size ->
            RadioChip(text = stringResource(when(size) {
                ReaderBackgroundSize.COVER -> Res.string.reader_palette_size_cover
                ReaderBackgroundSize.CONTAIN -> Res.string.reader_palette_size_contain
                ReaderBackgroundSize.ORIGINAL -> Res.string.reader_palette_size_original
            }), checked = settings.size == size, onClick = { if (editable) onChange(settings.copy(size = size)) })
        }
    }
    ReaderBackgroundPosition.entries.chunked(3).forEach { positions ->
        Row(Modifier.fillMaxWidth()) {
            positions.forEach { position ->
                RadioChip(text = stringResource(when(position) {
                    ReaderBackgroundPosition.TOP_LEFT -> Res.string.reader_palette_position_top_left
                    ReaderBackgroundPosition.TOP_CENTER -> Res.string.reader_palette_position_top_center
                    ReaderBackgroundPosition.TOP_RIGHT -> Res.string.reader_palette_position_top_right
                    ReaderBackgroundPosition.CENTER_LEFT -> Res.string.reader_palette_position_center_left
                    ReaderBackgroundPosition.CENTER -> Res.string.reader_palette_position_center
                    ReaderBackgroundPosition.CENTER_RIGHT -> Res.string.reader_palette_position_center_right
                    ReaderBackgroundPosition.BOTTOM_LEFT -> Res.string.reader_palette_position_bottom_left
                    ReaderBackgroundPosition.BOTTOM_CENTER -> Res.string.reader_palette_position_bottom_center
                    ReaderBackgroundPosition.BOTTOM_RIGHT -> Res.string.reader_palette_position_bottom_right
                }), checked = settings.position == position, modifier = Modifier.weight(1f), onClick = { if (editable) onChange(settings.copy(position = position)) })
            }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        ReaderBackgroundBlend.entries.forEach { blend ->
            RadioChip(text = stringResource(when(blend) {
                ReaderBackgroundBlend.NORMAL -> Res.string.reader_palette_blend_normal
                ReaderBackgroundBlend.MULTIPLY -> Res.string.reader_palette_blend_multiply
                ReaderBackgroundBlend.LIGHTEN -> Res.string.reader_palette_blend_lighten
                ReaderBackgroundBlend.OVERLAY -> Res.string.reader_palette_blend_overlay
                ReaderBackgroundBlend.SOFT_LIGHT -> Res.string.reader_palette_blend_soft_light
                ReaderBackgroundBlend.SCREEN -> Res.string.reader_palette_blend_screen
                ReaderBackgroundBlend.DARKEN -> Res.string.reader_palette_blend_darken
            }), checked = settings.blend == blend, onClick = { if (editable) onChange(settings.copy(blend = blend)) })
        }
    }
}

@Composable
private fun PalettePreview(palette: ReaderPalette, base: ReaderPaletteBaseColors, tab: ReaderPaletteTab, imageSource: String?, alpha: Float, settings: ReaderBackgroundSettings) {
    val imageVersion = ReaderBackgroundImageCache.version
    val bitmap = remember(imageSource, imageVersion) { imageSource?.let { ReaderBackgroundImageCache.peek(it) } }
    val failed = remember(imageSource, imageVersion) { imageSource?.let { ReaderBackgroundImageCache.isFailed(it) } == true }
    LaunchedEffect(imageSource) { imageSource?.let { ReaderBackgroundImageCache.requestAsync(it) } }
    Box(Modifier.fillMaxWidth().background(Color(base.background).copy(alpha = 1f))) {
        bitmap?.let { Canvas(Modifier.matchParentSize()) { drawReaderBackgroundBitmap(it, alpha, settings) } }
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (imageSource != null && bitmap == null) {
            Text(stringResource(if (failed) Res.string.reader_palette_image_failed else Res.string.reader_palette_image_loading), color = Color(base.text))
        }
        if (tab == ReaderPaletteTab.READER) {
            Text(
                stringResource(Res.string.reader_palette_sample_title),
                color = Color(palette.chapterTitleColor ?: base.text),
                fontSize = 18.sp
            )
            val sample = stringResource(Res.string.reader_palette_sample)
            val preview = remember(sample, palette) {
                buildAnnotatedString {
                    append(sample)
                    ReaderPalettePreview.match(sample, palette).forEach { match ->
                        addStyle(
                            SpanStyle(
                                color = match.foregroundColor?.let { Color(it) } ?: Color.Unspecified,
                                background = match.backgroundColor?.let { Color(it) } ?: Color.Unspecified
                            ),
                            match.start, match.endExclusive
                        )
                    }
                }
            }
            Text(preview, color = Color(base.text), fontSize = 16.sp)
        } else {
            HighlightSample(
                Res.string.reader_palette_sample_search,
                palette.searchResultColor, palette.searchResultBackgroundColor, base
            )
            HighlightSample(
                Res.string.reader_palette_sample_bookmark,
                palette.bookmarkColor, palette.bookmarkBackgroundColor, base
            )
            HighlightSample(
                Res.string.reader_palette_sample_annotation,
                palette.annotationColor, palette.annotationBackgroundColor, base
            )
        }
    }
    }
}

@Composable
private fun HighlightSample(
    text: StringResource,
    foreground: Int?,
    background: Int?,
    base: ReaderPaletteBaseColors
) {
    val sample = stringResource(text)
    Text(
        buildAnnotatedString {
            append(sample)
            addStyle(
                SpanStyle(
                    color = Color(foreground ?: base.text),
                    background = background?.let { Color(it) } ?: Color.Transparent
                ),
                0, length
            )
        },
        fontSize = 16.sp
    )
}

private data class PaletteField(
    val tab: ReaderPaletteTab,
    val key: String,
    val label: StringResource,
    val property: KMutableProperty1<ReaderPalette, Int?>
)

private val paletteFields = listOf(
    PaletteField(ReaderPaletteTab.READER, "chapterTitle", Res.string.reader_palette_chapter, ReaderPalette::chapterTitleColor),
    PaletteField(ReaderPaletteTab.READER, "quoteSymbol", Res.string.reader_palette_quote_symbol, ReaderPalette::quoteSymbolColor),
    PaletteField(ReaderPaletteTab.READER, "quoteContent", Res.string.reader_palette_quote_content, ReaderPalette::quoteContentColor),
    PaletteField(ReaderPaletteTab.READER, "bracketSymbol", Res.string.reader_palette_bracket_symbol, ReaderPalette::bracketSymbolColor),
    PaletteField(ReaderPaletteTab.READER, "bracketContent", Res.string.reader_palette_bracket_content, ReaderPalette::bracketContentColor),
    PaletteField(ReaderPaletteTab.READER, "punctuation", Res.string.reader_palette_punctuation, ReaderPalette::punctuationColor),
    PaletteField(ReaderPaletteTab.READER, "specialMark", Res.string.reader_palette_special, ReaderPalette::specialMarkColor),
    PaletteField(ReaderPaletteTab.READER, "number", Res.string.reader_palette_number, ReaderPalette::numberColor),
    PaletteField(ReaderPaletteTab.READER, "letter", Res.string.reader_palette_letter, ReaderPalette::letterColor),
    PaletteField(ReaderPaletteTab.HIGHLIGHT, "searchResult", Res.string.reader_palette_search, ReaderPalette::searchResultColor),
    PaletteField(ReaderPaletteTab.HIGHLIGHT, "searchResultBackground", Res.string.reader_palette_search_background, ReaderPalette::searchResultBackgroundColor),
    PaletteField(ReaderPaletteTab.HIGHLIGHT, "bookmark", Res.string.reader_palette_bookmark, ReaderPalette::bookmarkColor),
    PaletteField(ReaderPaletteTab.HIGHLIGHT, "bookmarkBackground", Res.string.reader_palette_bookmark_background, ReaderPalette::bookmarkBackgroundColor),
    PaletteField(ReaderPaletteTab.HIGHLIGHT, "annotation", Res.string.reader_palette_annotation, ReaderPalette::annotationColor),
    PaletteField(ReaderPaletteTab.HIGHLIGHT, "annotationBackground", Res.string.reader_palette_annotation_background, ReaderPalette::annotationBackgroundColor)
)
