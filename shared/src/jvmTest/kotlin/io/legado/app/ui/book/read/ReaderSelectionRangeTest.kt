package io.legado.app.ui.book.read

import io.legado.app.ui.book.read.page.PageSelPos
import io.legado.app.ui.book.read.page.PageSelectionState
import io.legado.app.ui.book.read.page.SelectionPageSource
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import org.junit.Assert.*
import org.junit.Test

class ReaderSelectionRangeTest {
    @Test
    fun relayoutCannotReuseSelectionCoordinatesFromAnOlderPage() {
        fun page(text: String) = TextPage(chapterIndex = 0).apply {
            addLine(TextLine(text = text).apply {
                addColumn(TextColumn(0f, 1f, text))
            })
        }
        var visible = page("a")
        val original = visible
        val selection = PageSelectionState().apply {
            pageSource = object : SelectionPageSource {
                override val isScroll = false
                override val visibleHeight = 100f
                override fun pageAt(pagePos: Int) = visible.takeIf { pagePos == 0 }
                override fun relativeOffset(pagePos: Int) = 0f
            }
        }
        selection.selectRange(original, PageSelPos(0, 0, 0), PageSelPos(0, 0, 0))
        visible = page("b")

        assertNull(selection.chapterRange(TextChapterShared(0, listOf(visible))))
    }

    @Test
    fun selectionEntirelyInNextVisibleChapterRetainsItsOwnIdentity() {
        fun page(index: Int) = TextPage(chapterIndex = index).apply {
            addLine(TextLine(text = "a").apply { addColumn(TextColumn(0f, 1f, "a")) })
        }
        val current = page(0)
        val next = page(1)
        val selection = PageSelectionState().apply {
            pageSource = object : SelectionPageSource {
                override val isScroll = true
                override val visibleHeight = 100f
                override fun pageAt(pagePos: Int) = listOf(current, next).getOrNull(pagePos)
                override fun relativeOffset(pagePos: Int) = pagePos * 10f
            }
        }
        selection.selectRange(current, PageSelPos(1, 0, 0), PageSelPos(1, 0, 0))
        assertEquals(1, selection.selectedChapterIndex)
        assertEquals("a", selection.chapterRange(TextChapterShared(1, listOf(next)))?.text)
    }

    @Test
    fun selectionUsesCanonicalUtf16OffsetsInsteadOfTrimmedDisplayText() {
        val line = TextLine(text = "  A😀B", chapterPosition = 0, indentSize = 2, isParagraphEnd = true)
        listOf(" ", " ", "A", "😀", "B").forEachIndexed { i, char ->
            line.addColumn(TextColumn(i.toFloat(), i + 1f, char))
        }
        val page = TextPage(chapterIndex = 2).apply { addLine(line) }
        val chapter = TextChapterShared(2, listOf(page))
        val selection = PageSelectionState()
        selection.selectRange(page, PageSelPos(0, 0, 2), PageSelPos(0, 0, 3))
        val range = selection.chapterRange(chapter)
        assertNotNull(range)
        assertEquals(2, range!!.chapterIndex)
        assertEquals(2, range.start)
        assertEquals(5, range.endExclusive)
        assertEquals("A😀", range.text)
    }

    @Test
    fun crossChapterAndStalePageSelectionsAreRejected() {
        fun page(index: Int) = TextPage(chapterIndex = index).apply {
            addLine(TextLine(text = "a").apply { addColumn(TextColumn(0f, 1f, "a")) })
        }
        val first = page(0)
        val second = page(1)
        val selection = PageSelectionState().apply {
            pageSource = object : SelectionPageSource {
                override val isScroll = true
                override val visibleHeight = 100f
                override fun pageAt(pagePos: Int) = listOf(first, second).getOrNull(pagePos)
                override fun relativeOffset(pagePos: Int) = pagePos * 10f
            }
        }
        selection.selectRange(first, PageSelPos(0, 0, 0), PageSelPos(1, 0, 0))
        assertNull(selection.chapterRange(TextChapterShared(0, listOf(first))))
        selection.selectRange(first, PageSelPos(0, 0, 0), PageSelPos(0, 0, 0))
        assertNull(selection.chapterRange(TextChapterShared(0, listOf(page(0)))))
        selection.cancel()
        assertNull(selection.chapterRange(TextChapterShared(0, listOf(first))))
    }
}
