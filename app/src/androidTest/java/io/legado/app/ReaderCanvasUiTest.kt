package io.legado.app

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.ui.book.read.page.PageContentCanvas
import io.legado.app.ui.book.read.page.PageSelPos
import io.legado.app.ui.book.read.page.PageSelectionState
import io.legado.app.ui.book.read.page.ReaderDrawStyle
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.overlay.SearchHighlightOverlay
import io.legado.app.ui.main.MainActivity
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real decoration projection, selection state and Canvas rendering.
 * Fixed density isolates pixel assertions from device font/display scaling.
 * This is not a repository deletion, gesture dispatch or image loading test.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class ReaderCanvasUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val drawTick = mutableIntStateOf(0)
    private val selection = PageSelectionState()
    private val search = mutableStateOf<SearchHighlightOverlay?>(null)
    private val line = TextLine(
        text = "M N",
        lineTop = 24f,
        lineBase = 68f,
        lineBottom = 84f,
        chapterPosition = 0,
        isParagraphEnd = true,
    ).apply {
        text.forEachIndexed { index, char ->
            addColumn(TextColumn(20f + index * 64f, 84f + index * 64f, char.toString()))
        }
    }
    private val page = TextPage(chapterIndex = 0).apply {
        visibleHeight = 140
        visibleBottom = 140
        addLine(line)
    }
    private val chapter = TextChapterShared(0, listOf(page))
    private val rules = listOf(
        ReadColorRule(
            bookUrl = BOOK_URL,
            keyword = "M",
            foregroundColor = AUTO_FOREGROUND,
            backgroundColor = AUTO_BACKGROUND,
        ),
    )
    private val highlight = BookHighlight(
        time = 101L,
        bookUrl = BOOK_URL,
        chapterIndex = 0,
        chapterPos = 0,
        chapterPosEnd = 1,
        bookText = "M",
        foregroundColor = MANUAL_FOREGROUND,
        backgroundColor = MANUAL_BACKGROUND,
    )
    private val style = ReaderDrawStyle(
        contentStyle = TextStyle(fontSize = 32.sp, fontFamily = FontFamily.Monospace),
        titleStyle = TextStyle(fontSize = 32.sp, fontFamily = FontFamily.Monospace),
        letterSpacingEm = 0f,
        textColor = Color.Black,
        accentColor = Color.Magenta,
        selectedColor = Color(0x800000FF),
        searchTextColor = Color.Magenta,
        searchColor = Color.Yellow,
        reviewColor = Color.Gray,
        reviewTextSize = 12.sp,
        bgColor = Color.White,
        backgroundImageSource = null,
        backgroundImageAlpha = 1f,
        tipColor = Color.Black,
        underline = false,
        isEInk = false,
    )

    @Test
    fun opaqueSearchBackgroundKeepsMatchedGlyphVisibleAndClearsToManualDecoration() {
        chapter.applyDecorations(BOOK_URL, rules, listOf(highlight))
        search.value = SearchHighlightOverlay(chapterIndex = 0, start = 0, endExclusive = 1)
        showCanvas()

        val searched = capture("search-opaque-background").toPixelMap()
        assertColorNear("Search uses the configured opaque background", Color.Yellow, searched[78, 30])
        assertTrue("Search glyph must remain visible above an opaque search background", countColor(
            searched, style.searchTextColor, 20, 24, 84, 84,
        ) > 8)

        compose.runOnIdle { search.value = null }
        val cleared = capture("search-cleared").toPixelMap()
        assertPainted(cleared, MANUAL_FOREGROUND, MANUAL_BACKGROUND)
        assertControlUnchanged(searched, cleared)
    }

    @Test
    fun boldRuleUpdateRebuildsGlyphStyleWithoutLosingColorOrMovingControl() {
        val normalRule = rules.single().copy(bold = false)
        chapter.applyDecorations(BOOK_URL, listOf(normalRule), emptyList())
        showCanvas()
        val normal = capture("rule-normal").toPixelMap()
        compose.runOnIdle {
            chapter.applyDecorations(BOOK_URL, listOf(normalRule.copy(bold = true)), emptyList())
            drawTick.intValue++
        }
        val bold = capture("rule-bold").toPixelMap()
        assertPainted(bold, AUTO_FOREGROUND, AUTO_BACKGROUND)
        assertTrue("Changing bold must update an already cached glyph", (24 until 84).any { y ->
            (20 until 84).any { x -> !near(normal[x, y], bold[x, y]) }
        })
        assertControlUnchanged(normal, bold)
    }

    @Test
    fun manualDecorationPaintsAndRemovingProjectionRestoresAutomaticColors() {
        chapter.applyDecorations(BOOK_URL, rules, listOf(highlight))
        showCanvas()
        val manual = capture("decoration-manual").toPixelMap()
        assertPainted(manual, MANUAL_FOREGROUND, MANUAL_BACKGROUND)

        compose.runOnIdle {
            chapter.applyDecorations(BOOK_URL, rules, emptyList())
            drawTick.intValue++
        }
        val automatic = capture("decoration-automatic-restored").toPixelMap()
        assertPainted(automatic, AUTO_FOREGROUND, AUTO_BACKGROUND)
        assertEquals(null, (line.columns.first() as TextColumn).manualHighlightId)
        assertTrue("Manual foreground must disappear after reprojection", countColor(
            automatic, Color(MANUAL_FOREGROUND), 20, 24, 84, 84,
        ) == 0)
        assertControlUnchanged(manual, automatic)
    }

    @Test
    fun selectionOverlayRepaintsAndCancelRestoresDecoratedPixels() {
        chapter.applyDecorations(BOOK_URL, rules, listOf(highlight))
        showCanvas()
        val before = capture("selection-before").toPixelMap()
        assertPainted(before, MANUAL_FOREGROUND, MANUAL_BACKGROUND)

        compose.runOnIdle {
            selection.selectRange(page, PageSelPos(0, 0, 0), PageSelPos(0, 0, 0))
            assertTrue(selection.isActive)
            assertEquals("M", selection.chapterRange(chapter)?.text)
        }
        // No drawTick change: this must be invalidated by the real selection.tick subscription.
        val selected = capture("selection-overlay").toPixelMap()
        assertColorNear(
            "Selection blends over the existing decoration",
            style.selectedColor.compositeOver(Color(MANUAL_BACKGROUND)),
            selected[78, 30],
        )
        assertTrue("Selection must change the decorated glyph region", (24 until 84).any { y ->
            (20 until 84).any { x -> !near(before[x, y], selected[x, y]) }
        })
        assertControlUnchanged(before, selected)

        compose.runOnIdle { selection.cancel() }
        val cleared = capture("selection-cleared").toPixelMap()
        assertFalse(selection.isActive)
        assertEquals(null, selection.chapterRange(chapter))
        assertPainted(cleared, MANUAL_FOREGROUND, MANUAL_BACKGROUND)
        for (y in 24 until 84) {
            for (x in 20 until 212) {
                assertColorNear("Cancel restores pixel ($x,$y)", before[x, y], cleared[x, y])
            }
        }
    }

    private fun showCanvas() {
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    PageContentCanvas(
                        textPage = page,
                        modifier = Modifier.requiredSize(260.dp, 140.dp)
                            .background(Color.White).testTag(CANVAS_TAG),
                        style = style,
                        drawTick = drawTick.intValue,
                        selection = selection,
                        searchHighlight = search.value,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String): ImageBitmap {
        compose.waitForIdle()
        val image = compose.onNodeWithTag(CANVAS_TAG).captureToImage()
        val directory = File(compose.activity.cacheDir, "reader-canvas-ui")
        assertTrue("Cannot create screenshot directory", directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$name.png")
        file.outputStream().use {
            assertTrue("PNG encoding failed", image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        Log.i("ReaderCanvasUiTest", "Screenshot: ${file.absolutePath}")
        assertTrue("Screenshot must not be empty", file.length() > 0)
        assertEquals(260, image.width)
        assertEquals(140, image.height)
        return image
    }

    private fun assertPainted(pixels: PixelMap, foreground: Int, background: Int) {
        assertColorNear("Decoration background", Color(background), pixels[78, 30])
        assertTrue("Expected colored glyphs, not just a nonblank rectangle", countColor(
            pixels, Color(foreground), 20, 24, 84, 84,
        ) > 8)
        assertTrue("Unmatched control glyph must retain base text color", countColor(
            pixels, Color.Black, 148, 24, 212, 84,
        ) > 8)
        assertColorNear("Outside the line stays white", Color.White, pixels[240, 110])
    }

    private fun assertControlUnchanged(before: PixelMap, after: PixelMap) {
        for (y in 24 until 84) {
            for (x in 148 until 212) {
                assertColorNear("Control pixel ($x,$y)", before[x, y], after[x, y])
            }
        }
    }

    private fun countColor(pixels: PixelMap, color: Color, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (near(color, pixels[x, y])) count++
            }
        }
        return count
    }

    private fun assertColorNear(message: String, expected: Color, actual: Color) {
        assertTrue("$message: expected=$expected actual=$actual", near(expected, actual))
    }

    private fun near(a: Color, b: Color): Boolean =
        abs(a.red - b.red) <= 3f / 255f &&
            abs(a.green - b.green) <= 3f / 255f &&
            abs(a.blue - b.blue) <= 3f / 255f &&
            abs(a.alpha - b.alpha) <= 3f / 255f

    companion object {
        private const val CANVAS_TAG = "reader-canvas-under-test"
        private const val BOOK_URL = "test://reader-canvas"
        private val AUTO_FOREGROUND = 0xffcc0000.toInt()
        private val AUTO_BACKGROUND = 0xffffe080.toInt()
        private val MANUAL_FOREGROUND = 0xff006600.toInt()
        private val MANUAL_BACKGROUND = 0xff80e0e0.toInt()
    }
}
