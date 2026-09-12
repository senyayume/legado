package io.legado.app.model.read

import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.ui.book.read.page.entities.TextChapterDecorator
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.SimpleChapterLayout
import io.legado.app.ui.book.read.page.provider.SimpleTextMeasurer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChapterDecoratorTest {

    @Test
    fun projectionUsesEarliestIntersectingRangeAcrossGraphemesGapsAndPages() {
        val first = TextLine(text = "a\u0301x\uD83D\uDE00", isParagraphEnd = true).apply {
            addColumn(TextColumn(0f, 10f, "a\u0301"))
            addColumn(TextColumn(10f, 20f, "x"))
            addColumn(TextColumn(20f, 30f, "\uD83D\uDE00"))
        }
        val second = TextLine(text = "z", chapterPosition = 6, isParagraphEnd = true).apply {
            addColumn(TextColumn(0f, 10f, "z"))
        }
        val chapter = TextChapterShared(0,
            listOf(TextPage().apply { addLine(first) }, TextPage().apply { addLine(second) }))
        chapter.applyDecorations("book", listOf(
            ReadColorRule(keyword = "a", foregroundColor = 1),
            ReadColorRule(keyword = "\u0301", foregroundColor = 2),
            ReadColorRule(keyword = "\uD83D\uDE00", foregroundColor = 3),
            ReadColorRule(keyword = "z", foregroundColor = 4),
        ), emptyList())
        assertEquals(listOf(1, null, 3, 4),
            (first.columns + second.columns).filterIsInstance<TextColumn>().map { it.foregroundColor })
    }

    @Test
    fun manualHighlightUsesCanonicalOffsetsIncludingGeometricIndent() = runBlocking {
        val chapter = chapter("catcat", "  ")
        val line = chapter.pages.first().lines.first()
        val positions = chapter.pages.flatMap { it.lines }.map { it.chapterPosition }
        val columns = line.columns.filterIsInstance<TextColumn>()
        assertEquals(2, line.indentSize)
        assertEquals("  catcat", line.text)
        val start = line.chapterPosition + columns.take(line.indentSize).sumOf { it.charData.length }

        chapter.applyDecorations(
            bookUrl = "book",
            rules = emptyList(),
            highlights = listOf(
                BookHighlight(
                    time = 42L,
                    bookUrl = "book",
                    chapterPos = start,
                    chapterPosEnd = start + 3,
                    bookText = "cat",
                    backgroundColor = 9,
                )
            ),
        )

        assertEquals(listOf(9, 9, 9), columns.drop(2).take(3).map { it.backgroundColor })
        assertEquals(listOf(42L, 42L, 42L), columns.drop(2).take(3).map { it.manualHighlightId })
        assertTrue(columns.take(2).all { it.backgroundColor == null })
        assertTrue(columns.take(2).all { it.manualHighlightId == null })
        assertTrue(columns.drop(5).all { it.backgroundColor == null })
        assertTrue(columns.drop(5).all { it.manualHighlightId == null })
        assertEquals(positions, chapter.pages.flatMap { it.lines }.map { it.chapterPosition })
    }

    @Test
    fun removingManualDecorationKeepsAutomaticRuleAndPageGeometry() = runBlocking {
        val chapter = chapter("cat")
        val columns = chapter.pages.flatMap { it.lines }.flatMap { it.columns }
            .filterIsInstance<TextColumn>()
        val rule = ReadColorRule(keyword = "cat", foregroundColor = 7, underline = true)
        val geometry = columns.map { it.start to it.end }
        val highlight = BookHighlight(
            time = 42L,
            bookUrl = "book",
            chapterPos = 0,
            chapterPosEnd = 3,
            bookText = "cat",
            backgroundColor = 9,
        )
        chapter.applyDecorations("book", listOf(rule), listOf(highlight))
        assertTrue(columns.all { it.backgroundColor == 9 })
        assertTrue(columns.all { it.manualHighlightId == 42L })

        chapter.applyDecorations("book", listOf(rule), emptyList())

        columns.forEach {
            assertEquals(7, it.foregroundColor)
            assertEquals(true, it.underline)
            assertNull(it.backgroundColor)
            assertNull(it.manualHighlightId)
        }
        assertEquals(geometry, columns.map { it.start to it.end })
    }

    @Test
    fun canonicalHelpersPreserveIndentSurrogateImageAndParagraphBoundaries() {
        val first = TextLine(text = "  A\uD83D\uDE00\u25A9", indentSize = 2, isParagraphEnd = true)
        listOf(" ", " ", "A", "\uD83D\uDE00").forEachIndexed { index, text ->
            first.addColumn(TextColumn(index * 10f, (index + 1) * 10f, text))
        }
        first.addColumn(ImageColumn(40f, 50f, "image"))
        val second = TextLine(text = "B", chapterPosition = 7, isParagraphEnd = true)
        second.addColumn(TextColumn(0f, 10f, "B"))
        val pages = listOf(TextPage().apply { addLine(first) }, TextPage().apply { addLine(second) })

        val text = TextChapterDecorator.canonicalText(pages)

        assertEquals("  A\uD83D\uDE00\u25A9\nB\n", text)
        assertEquals(listOf(0, 1, 2, 3, 5, 6), (0..first.columns.size).map {
            TextChapterDecorator.columnOffset(first, it)
        })
        val start = TextChapterDecorator.columnOffset(first, 3)
        val end = TextChapterDecorator.columnOffset(second, second.columns.size)
        assertEquals(7, TextChapterDecorator.columnOffset(second, 0))
        assertEquals("\uD83D\uDE00\u25A9\nB", text.substring(start, end))
    }

    @Test
    fun reanchoredOverlappingHighlightsProjectLatestIdentityAndClearStaleIds() = runBlocking {
        val chapter = chapter("xcat")
        val columns = chapter.pages.single().lines.single().columns.filterIsInstance<TextColumn>()
        val older = BookHighlight(
            time = 10L, bookUrl = "book", chapterPos = 20, chapterPosEnd = 23,
            bookText = "cat", backgroundColor = 9,
        )
        val newer = BookHighlight(
            time = 20L, bookUrl = "book", chapterPos = 21, chapterPosEnd = 23,
            bookText = "at", foregroundColor = 7,
        )

        chapter.applyDecorations("book", emptyList(), listOf(newer, older))

        assertEquals(listOf(null, 10L, 20L, 20L), columns.map { it.manualHighlightId })
        assertEquals(listOf(null, 9, 9, 9), columns.map { it.backgroundColor })
        assertEquals(listOf(null, null, 7, 7), columns.map { it.foregroundColor })

        chapter.applyDecorations("book", emptyList(), listOf(older))
        assertEquals(listOf(null, 10L, 10L, 10L), columns.map { it.manualHighlightId })

        chapter.applyDecorations("book", emptyList(), listOf(older.copy(bookText = "missing")))
        assertTrue(columns.all { it.manualHighlightId == null && it.backgroundColor == null })
    }

    @Test
    fun canonicalOffsetsAgreeWithRealLayoutAcrossPages() = runBlocking {
        val chapter = chapter("cat".repeat(20), "  ", visibleHeight = 10)
        assertTrue(chapter.pages.size > 1)
        val text = TextChapterDecorator.canonicalText(chapter.pages)
        assertEquals("  " + "cat".repeat(20) + "\n", text)
        chapter.pages.flatMap { it.lines }.forEach { line ->
            val start = TextChapterDecorator.columnOffset(line, 0)
            val end = TextChapterDecorator.columnOffset(line, line.columns.size)
            assertEquals(line.chapterPosition, start)
            assertEquals(line.text, text.substring(start, end))
        }
    }

    @Test
    fun rejectedHighlightCountClearsIdentityWithoutReadingAnyRecords() = runBlocking {
        val chapter = chapter("cat")
        val columns = chapter.pages.single().lines.single().columns.filterIsInstance<TextColumn>()
        columns.forEach {
            it.manualHighlightId = 42L
            it.backgroundColor = 9
        }
        val oversized = object : AbstractList<BookHighlight>() {
            override val size = ColorRuleMatcher.MAX_RULES + 1
            override fun get(index: Int): BookHighlight =
                error("Oversized highlight input must be rejected before reading records")
        }

        chapter.applyDecorations(
            "book", listOf(ReadColorRule(keyword = "cat", foregroundColor = 7)), oversized
        )

        columns.forEach {
            assertNull(it.manualHighlightId)
            assertNull(it.backgroundColor)
            assertEquals(7, it.foregroundColor)
        }
    }

    @Test
    fun rejectedChapterLengthClearsIdentityWithoutReadingAnyRecords() {
        val text = "x".repeat(ColorRuleMatcher.MAX_CHAPTER_LENGTH + 1)
        val column = TextColumn(0f, 10f, text).apply {
            manualHighlightId = 42L
            backgroundColor = 9
        }
        val line = TextLine(text = text).apply { addColumn(column) }
        val page = TextPage().apply { addLine(line) }
        val highlights = object : AbstractList<BookHighlight>() {
            override val size = 1
            override fun get(index: Int): BookHighlight =
                error("Overlong chapter must be rejected before reading records")
        }

        TextChapterShared(0, listOf(page)).applyDecorations("book", emptyList(), highlights)

        assertNull(column.manualHighlightId)
        assertNull(column.backgroundColor)
    }

    private suspend fun chapter(
        text: String,
        indent: String = "",
        visibleHeight: Int = 100,
    ): TextChapterShared {
        val pages = SimpleChapterLayout(
            measurer = SimpleTextMeasurer(textSizePx = 10f),
            visibleWidth = 100,
            visibleHeight = visibleHeight,
            paddingLeft = 0,
            paddingTop = 0,
            textHeight = 10f,
            descent = 2f,
            lineSpacingExtra = 1f,
            paragraphSpacing = 0,
            titleTopSpacing = 0,
            titleBottomSpacing = 0,
            paragraphIndent = indent,
            textFullJustify = false,
            indentCharWidth = 5f,
            indentChar = " ",
        ).layout(
            displayTitle = "",
            contents = listOf(text),
            chapterIndex = 0,
            chapterSize = 1,
        )
        return TextChapterShared(chapterIndex = 0, pages = pages)
    }
}
