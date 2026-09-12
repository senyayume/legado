package io.legado.app.model.read

import io.legado.app.data.dao.BookHighlightDao
import io.legado.app.data.entities.BookHighlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderHighlightCommandsTest {
    private val highlight = BookHighlight(
        time = 100, bookUrl = "book://one", chapterIndex = 2,
        chapterPos = 4, chapterPosEnd = 8, bookText = "text",
        foregroundColor = 0xff008000.toInt(),
    )

    @Test
    fun saveFailurePreservesSelectionAndDoesNotRefresh() = runBlocking {
        val dao = MemoryHighlights().apply { failInsert = true }
        var selected = true
        var refreshed = false
        val result = ReaderHighlightCommands(BookHighlightRepository(dao)).save(
            highlight, refresh = { refreshed = true }, onSuccess = { selected = false },
        )
        assertTrue(result.isFailure)
        assertTrue(selected)
        assertFalse(refreshed)
    }

    @Test
    fun successfulSaveRefreshesBeforeClearingSelectionAndRetryIsIdempotent() = runBlocking {
        val dao = MemoryHighlights()
        val commands = ReaderHighlightCommands(BookHighlightRepository(dao))
        var selected = true
        val events = mutableListOf<String>()
        val refresh: suspend () -> Unit = {
            assertTrue(selected)
            assertEquals(1, dao.rows.size)
            events += "refresh"
        }
        assertTrue(commands.save(highlight, refresh) { selected = false; events += "clear" }.isSuccess)
        assertEquals(listOf("refresh", "clear"), events)
        assertTrue(commands.save(highlight.copy(time = 101), {}, {}).isSuccess)
        assertEquals(listOf(highlight), dao.rows)
    }

    @Test
    fun refreshFailurePreservesSelectionAndCanBeRetried() = runBlocking {
        val dao = MemoryHighlights()
        val commands = ReaderHighlightCommands(BookHighlightRepository(dao))
        var selected = true
        assertTrue(commands.save(highlight, { error("refresh unavailable") }) { selected = false }.isFailure)
        assertTrue(selected)
        assertEquals(1, dao.rows.size)
        assertTrue(commands.save(highlight, {}) { selected = false }.isSuccess)
        assertFalse(selected)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun invalidAndMismatchedIntervalsNeverReachPersistence() = runBlocking {
        val dao = MemoryHighlights()
        val repository = BookHighlightRepository(dao)
        for (invalid in listOf(
            highlight.copy(bookText = ""),
            highlight.copy(bookText = "text", chapterPosEnd = 7),
            highlight.copy(chapterIndex = -1),
            highlight.copy(bookUrl = ""),
            highlight.copy(bookText = "x".repeat(10_001), chapterPosEnd = 10_005),
        )) assertTrue(repository.save(invalid).isFailure)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun deletionRefreshesOnlyAfterRemovingTheExactRecord() = runBlocking {
        val dao = MemoryHighlights().apply {
            rows += highlight
            rows += highlight.copy(time = 200, bookUrl = "book://two")
        }
        var refreshed = false
        assertTrue(ReaderHighlightCommands(BookHighlightRepository(dao)).delete(highlight) {
            refreshed = true
            assertEquals(listOf("book://two"), dao.rows.map { it.bookUrl })
        }.isSuccess)
        assertTrue(refreshed)
    }

    @Test
    fun cancellationIsNotConvertedIntoAUserError() = runBlocking {
        val commands = ReaderHighlightCommands(BookHighlightRepository(MemoryHighlights()))
        var cleared = false
        try {
            commands.save(highlight, { throw CancellationException() }) { cleared = true }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(cleared)
        }
    }

    private class MemoryHighlights : BookHighlightDao {
        val rows = mutableListOf<BookHighlight>()
        var failInsert = false
        override suspend fun getByBook(bookUrl: String) = rows.filter { it.bookUrl == bookUrl }
        override suspend fun getByChapter(bookUrl: String, chapterIndex: Int) =
            rows.filter { it.bookUrl == bookUrl && it.chapterIndex == chapterIndex }
        override suspend fun findExact(bookUrl: String, chapterIndex: Int, chapterPos: Int, chapterPosEnd: Int) =
            rows.firstOrNull {
                it.bookUrl == bookUrl && it.chapterIndex == chapterIndex &&
                    it.chapterPos == chapterPos && it.chapterPosEnd == chapterPosEnd
            }
        override suspend fun insert(highlight: BookHighlight): Long {
            check(!failInsert) { "database unavailable" }
            if (rows.any { it.time == highlight.time } ||
                findExact(highlight.bookUrl, highlight.chapterIndex, highlight.chapterPos, highlight.chapterPosEnd) != null
            ) return -1L
            rows += highlight
            return highlight.time
        }
        override suspend fun delete(highlight: BookHighlight) {
            rows.removeAll { it.time == highlight.time && it.bookUrl == highlight.bookUrl }
        }
    }
}
