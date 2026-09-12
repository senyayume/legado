package io.legado.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.legado.app.model.read.ReaderPaletteDraft
import io.legado.app.model.read.ReaderPaletteDraftStatus
import io.legado.app.model.read.ReaderPaletteMode
import io.legado.app.model.read.detachedAppearanceCopy
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.model.read.ReaderBackgroundSize
import io.legado.app.model.read.ReaderBackgroundPosition
import io.legado.app.utils.KS_JSON
import java.io.File
import io.legado.app.ui.book.read.config.ReaderPaletteDialog
import io.legado.app.ui.book.read.config.BgImageItem
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.CompletableDeferred
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.cancel
import legado.shared.generated.resources.ok
import legado.shared.generated.resources.reader_palette_apply
import legado.shared.generated.resources.reader_palette_applying
import legado.shared.generated.resources.reader_palette_chapter
import legado.shared.generated.resources.reader_palette_day
import legado.shared.generated.resources.reader_palette_eink
import legado.shared.generated.resources.reader_palette_failure
import legado.shared.generated.resources.reader_palette_highlight
import legado.shared.generated.resources.reader_palette_night
import legado.shared.generated.resources.reader_palette_reader
import legado.shared.generated.resources.reader_palette_reset
import legado.shared.generated.resources.reader_palette_sample
import legado.shared.generated.resources.reader_palette_sample_search
import legado.shared.generated.resources.reader_palette_title
import legado.shared.generated.resources.reader_palette_background
import legado.shared.generated.resources.reader_palette_background_images
import legado.shared.generated.resources.reader_palette_bracket_enabled
import legado.shared.generated.resources.reader_palette_size_contain
import legado.shared.generated.resources.reader_palette_position_bottom_right
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated dialog regression; no book, network, route or persisted reader settings required.
 * Requires androidTest ui-test-junit4 and debug ui-test-manifest matching resolved Compose UI.
 * MainActivity registers the real platform capabilities before test content replaces its UI.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPaletteUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val original = ReadStyleConfig()
    private var draft = ReaderPaletteDraft(original)
    private val visible = mutableStateOf(true)
    private lateinit var labels: Map<StringResource, String>
    private var writes = 0
    private var dismisses = 0
    private var saved: ReadStyleConfig? = null

    @Test
    fun backgroundAndNightSettingsSurviveApplyAndReopeningFromDisk() {
        val file = File.createTempFile("reader-palette-ui-", ".json", compose.activity.cacheDir)
        try {
            showPalette { config ->
                file.writeText(KS_JSON.encodeToString(ReadStyleConfig.serializer(), config), Charsets.UTF_8)
                writes++
                Result.success(Unit)
            }
            chooseBackground("#FF123456")
            assertEquals(0xff123456.toInt(), draft.state.value.snapshot().bgColorForMode(ReaderPaletteMode.DAY))
            node(Res.string.reader_palette_background_images).performScrollTo().performClick()
            compose.onNodeWithText("羊皮纸1.jpg").performScrollTo().performClick()
            node(Res.string.reader_palette_size_contain).performScrollTo().performClick()
            node(Res.string.reader_palette_position_bottom_right).performScrollTo().performClick()
            node(Res.string.reader_palette_night).performScrollTo().performClick()
            chooseBackground("#FF203040")
            node(Res.string.reader_palette_apply).performClick()
            awaitStatus(ReaderPaletteDraftStatus.APPLIED)

            val persisted = KS_JSON.decodeFromString(ReadStyleConfig.serializer(), file.readText(Charsets.UTF_8))
            assertEquals(1, writes)
            assertEquals(1, persisted.backgroundTypeForMode(ReaderPaletteMode.DAY))
            assertEquals("羊皮纸1.jpg", persisted.backgroundForMode(ReaderPaletteMode.DAY))
            assertEquals(0xff203040.toInt(), persisted.bgColorForMode(ReaderPaletteMode.NIGHT))
            assertEquals(ReaderBackgroundSize.CONTAIN, persisted.backgroundSettingsForMode(ReaderPaletteMode.DAY).size)
            assertEquals(ReaderBackgroundPosition.BOTTOM_RIGHT, persisted.backgroundSettingsForMode(ReaderPaletteMode.DAY).position)
            assertEquals(original.backgroundSettingsForMode(ReaderPaletteMode.NIGHT), persisted.backgroundSettingsForMode(ReaderPaletteMode.NIGHT))

            compose.runOnIdle {
                draft = ReaderPaletteDraft(persisted)
                visible.value = true
            }
            showPalette()
            node(Res.string.reader_palette_size_contain).performScrollTo().assertIsDisplayed()
            node(Res.string.reader_palette_position_bottom_right).performScrollTo().assertIsDisplayed()
            assertEquals(ReaderBackgroundSize.CONTAIN, draft.state.value.snapshot().backgroundSettingsForMode(ReaderPaletteMode.DAY).size)
            assertEquals(ReaderBackgroundPosition.BOTTOM_RIGHT, draft.state.value.snapshot().backgroundSettingsForMode(ReaderPaletteMode.DAY).position)
            node(Res.string.reader_palette_night).performScrollTo().performClick()
            assertPreviewMode(ReaderPaletteMode.NIGHT)
            assertEquals(0xff203040.toInt(), draft.state.value.snapshot().bgColorForMode(ReaderPaletteMode.NIGHT))
            node(Res.string.cancel).performClick()
            awaitStatus(ReaderPaletteDraftStatus.CANCELLED)
            assertEquals(1, writes)
        } finally {
            assertTrue("Isolated palette file must be removable", !file.exists() || file.delete())
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun dialogRendersNonblankPixelsAndMatchedPreview() {
        showPalette()
        compose.onNode(isDialog()).assertIsDisplayed()
        node(Res.string.reader_palette_title).assertIsDisplayed()
        node(Res.string.reader_palette_apply).assertIsDisplayed()
        node(Res.string.cancel).assertIsDisplayed()
        assertPreviewMode(ReaderPaletteMode.DAY)

        val pixels = compose.onNode(isDialog()).captureToImage().toPixelMap()
        assertTrue(pixels.width > 0 && pixels.height > 0)
        val samples = buildSet {
            for (y in 0 until pixels.height step maxOf(1, pixels.height / 40)) {
                for (x in 0 until pixels.width step maxOf(1, pixels.width / 40)) {
                    add(pixels[x, y])
                }
            }
        }
        assertTrue("Dialog capture must contain painted content, not a uniform surface", samples.size > 1)

        node(Res.string.reader_palette_highlight).performScrollTo().performClick()
        node(Res.string.reader_palette_highlight).assertIsSelected()
        node(Res.string.reader_palette_sample_search).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cancelDiscardsUiEditsWithoutCallingWriter() {
        showPalette()
        bracketToggle().performScrollTo().assertIsOn().performClick().assertIsOff()

        node(Res.string.cancel).performClick()
        awaitStatus(ReaderPaletteDraftStatus.CANCELLED)

        compose.runOnIdle {
            assertEquals(ReaderPaletteDraftStatus.CANCELLED, draft.state.value.status)
            assertEquals(ReadStyleConfig(), original)
            assertEquals(0, writes)
            assertEquals(1, dismisses)
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun modeSwitchingRetainsIndependentEditsAndUpdatesMatchedPreview() {
        showPalette()
        bracketToggle().performScrollTo().performClick().assertIsOff()

        node(Res.string.reader_palette_night).performScrollTo().performClick()
        assertPreviewMode(ReaderPaletteMode.NIGHT)
        bracketToggle().performScrollTo().assertIsOn().performClick().assertIsOff()

        node(Res.string.reader_palette_eink).performScrollTo().performClick()
        assertPreviewMode(ReaderPaletteMode.EINK)
        bracketToggle().performScrollTo().assertIsOn()

        node(Res.string.reader_palette_day).performScrollTo().performClick()
        bracketToggle().performScrollTo().assertIsOff()
        compose.runOnIdle {
            assertFalse(draft.state.value.palette(ReaderPaletteMode.DAY).bracketPairsEnabled)
            assertFalse(draft.state.value.palette(ReaderPaletteMode.NIGHT).bracketPairsEnabled)
            assertTrue(draft.state.value.palette(ReaderPaletteMode.EINK).bracketPairsEnabled)
            assertEquals(0, writes)
            assertEquals(ReadStyleConfig(), original)
        }
    }

    @Test
    fun colorChooserAndApplyDeliverEditedPaletteExactlyOnce() {
        showPalette()
        compose.onNodeWithContentDescription(labels.getValue(Res.string.reader_palette_chapter))
            .performScrollTo().performClick()
        val hexField = compose.onNode(hasSetTextAction())
        if (hasAnyAncestor(hasScrollAction()).matches(hexField.fetchSemanticsNode())) {
            hexField.performScrollTo()
        }
        hexField.assertIsDisplayed().performTextReplacement("#FF123456")
        node(Res.string.ok).performClick()
        compose.runOnIdle {
            assertEquals(0xff123456.toInt(), draft.state.value.palette(ReaderPaletteMode.DAY).chapterTitleColor)
            assertEquals(0, writes)
        }

        node(Res.string.reader_palette_apply).performClick()
        awaitStatus(ReaderPaletteDraftStatus.APPLIED)

        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(1, dismisses)
            assertNotNull(saved)
            assertEquals(0xff123456.toInt(), saved?.readerPalette?.day?.chapterTitleColor)
            assertEquals(original.readerPalette.night, saved?.readerPalette?.night)
            assertEquals(original.readerPalette.eInk, saved?.readerPalette?.eInk)
            assertEquals(ReadStyleConfig(), original)
            assertEquals(ReaderPaletteDraftStatus.APPLIED, draft.state.value.status)
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun failedApplyKeepsDialogAndEditsUntilExplicitRetrySucceeds() {
        showPalette { palettes ->
            writes++
            if (writes == 1) Result.failure(IllegalStateException("test write failure"))
            else {
                saved = palettes.detachedAppearanceCopy()
                Result.success(Unit)
            }
        }
        bracketToggle().performScrollTo().performClick()
        node(Res.string.reader_palette_apply).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            draft.state.value.failure != null
        }

        node(Res.string.reader_palette_failure).performScrollTo().assertIsDisplayed()
        bracketToggle().performScrollTo().assertIsOff()
        node(Res.string.reader_palette_apply).assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(0, dismisses)
            assertEquals(ReaderPaletteDraftStatus.EDITING, draft.state.value.status)
            assertEquals(ReadStyleConfig(), original)
        }

        node(Res.string.reader_palette_apply).performClick()
        awaitStatus(ReaderPaletteDraftStatus.APPLIED)

        compose.runOnIdle {
            assertEquals(2, writes)
            assertEquals(1, dismisses)
            assertEquals(false, saved?.readerPalette?.day?.bracketPairsEnabled)
            assertEquals(ReaderPaletteDraftStatus.APPLIED, draft.state.value.status)
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun suspendedApplyDisablesCommandsUntilCompletion() {
        val release = CompletableDeferred<Unit>()
        showPalette { palettes ->
            writes++
            release.await()
            saved = palettes.detachedAppearanceCopy()
            Result.success(Unit)
        }
        try {
            node(Res.string.reader_palette_apply).performClick()
            awaitStatus(ReaderPaletteDraftStatus.APPLYING)

            node(Res.string.reader_palette_applying).assertIsNotEnabled()
            node(Res.string.reader_palette_reset).assertIsNotEnabled()
            node(Res.string.cancel).assertIsNotEnabled()
            bracketToggle().performScrollTo().assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(1, writes)
                assertEquals(0, dismisses)
                assertEquals(ReaderPaletteDraftStatus.APPLYING, draft.state.value.status)
            }
        } finally {
            release.complete(Unit)
        }
        awaitStatus(ReaderPaletteDraftStatus.APPLIED)
        compose.runOnIdle {
            assertEquals(1, writes)
            assertEquals(1, dismisses)
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    private fun showPalette(
        onApply: suspend (ReadStyleConfig) -> Result<Unit> = {
            writes++
            saved = it.detachedAppearanceCopy()
            Result.success(Unit)
        }
    ) {
        setReaderContent {
            labels = listOf(
                Res.string.cancel, Res.string.ok, Res.string.reader_palette_apply,
                Res.string.reader_palette_applying, Res.string.reader_palette_chapter,
                Res.string.reader_palette_day, Res.string.reader_palette_eink,
                Res.string.reader_palette_failure, Res.string.reader_palette_highlight,
                Res.string.reader_palette_night, Res.string.reader_palette_reader,
                Res.string.reader_palette_reset, Res.string.reader_palette_sample,
                Res.string.reader_palette_sample_search, Res.string.reader_palette_title,
                Res.string.reader_palette_background, Res.string.reader_palette_bracket_enabled,
                Res.string.reader_palette_background_images,
                Res.string.reader_palette_size_contain, Res.string.reader_palette_position_bottom_right
            ).associateWith { stringResource(it) }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides remember { AndroidThemeStoreProvider() },
                LocalAppConfigProvider provides remember { AndroidAppConfigProvider() },
                LocalEventBusProvider provides remember { AndroidEventBusProvider() }
            ) {
                AppTheme {
                    if (visible.value) {
                        ReaderPaletteDialog(
                            draft = draft,
                            initialMode = ReaderPaletteMode.DAY,
                            backgroundImages = listOf(BgImageItem("羊皮纸1.jpg", "羊皮纸1.jpg")),
                            onApply = onApply,
                            onDismiss = {
                                dismisses++
                                visible.value = false
                            }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun node(resource: StringResource) = compose.onNodeWithText(labels.getValue(resource))

    private fun bracketToggle() = compose.onNode(isToggleable() and
        hasContentDescription(labels.getValue(Res.string.reader_palette_bracket_enabled)))

    private fun chooseBackground(hex: String) {
        node(Res.string.reader_palette_background).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(hex)
        node(Res.string.ok).performClick()
    }

    private fun setReaderContent(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent(content = content) }
    }

    private fun awaitStatus(status: ReaderPaletteDraftStatus) {
        // Await the owner transition explicitly; UI idleness is not completion of a write.
        compose.waitUntil(timeoutMillis = 5_000) {
            draft.state.value.status == status
        }
    }

    private fun assertPreviewMode(mode: ReaderPaletteMode) {
        val sample = node(Res.string.reader_palette_sample).performScrollTo().assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val expected = Color(original.readerPalette.forMode(mode).quoteContentColor!!)
        assertTrue("Preview must use the selected mode's matched quote color",
            sample.spanStyles.any { it.item.color == expected })
    }

}
