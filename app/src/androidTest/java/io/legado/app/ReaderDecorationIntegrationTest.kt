package io.legado.app

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.model.read.BookHighlightRepository
import io.legado.app.model.read.ReaderHighlightCommands
import io.legado.app.ui.book.read.page.entities.TextChapterShared
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderDecorationIntegrationTest {
    @Test
    fun persistedHighlightProjectsImmediatelyAndDeletionRestoresAutomaticStyle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("reader-decoration-", ".db", context.cacheDir)
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        var database = openDatabase()
        try {
            val repository = BookHighlightRepository(database.bookHighlightDao)
            val highlight = BookHighlight(
                time = 200, bookUrl = "test://reader-decoration", chapterIndex = 0,
                chapterPos = 2, chapterPosEnd = 6, bookText = "test",
                foregroundColor = 0xff008000.toInt(),
            )
            val results = (0..3).map { offset ->
                async(Dispatchers.IO) { repository.save(highlight.copy(time = 200L + offset)) }
            }.awaitAll()
            assertTrue(results.all { it.isSuccess })
            database.readColorRuleDao.insert(ReadColorRule(
                bookUrl = highlight.bookUrl, keyword = "test", foregroundColor = 0xffff0000.toInt(),
            ))
            database.close()
            database = openDatabase()
            val saved = database.bookHighlightDao.getByChapter(highlight.bookUrl, 0).single()
            val line = TextLine(text = "  test", chapterPosition = 0, indentSize = 2, isParagraphEnd = true)
            line.text.forEachIndexed { index, char ->
                line.addColumn(TextColumn(index.toFloat(), index + 1f, char.toString()))
            }
            val page = TextPage(chapterIndex = 0).apply { addLine(line) }
            val chapter = TextChapterShared(0, listOf(page))
            suspend fun refresh() = chapter.applyDecorations(
                highlight.bookUrl, database.readColorRuleDao.getForBook(highlight.bookUrl),
                database.bookHighlightDao.getByChapter(highlight.bookUrl, 0),
            )
            refresh()
            val text = line.columns.filterIsInstance<TextColumn>().drop(2)
            assertTrue(text.all { it.foregroundColor == highlight.foregroundColor })
            assertTrue(text.all { it.manualHighlightId == saved.time })
            assertTrue(ReaderHighlightCommands(BookHighlightRepository(database.bookHighlightDao))
                .delete(saved, ::refresh).isSuccess)
            assertTrue(text.all { it.foregroundColor == 0xffff0000.toInt() })
            assertTrue(text.all { it.manualHighlightId == null })
            assertEquals(0, line.chapterPosition)
            assertEquals("  test", line.text)
        } finally {
            database.close()
            context.deleteDatabase(file.absolutePath)
        }
    }
}
