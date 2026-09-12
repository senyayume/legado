package io.legado.app.model.read

import io.legado.app.data.dao.ReadColorRuleDao
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.data.AppDatabase
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.legado.app.ui.book.read.config.ColorRuleScreenModel
import io.legado.app.ui.book.read.config.ColorRuleUiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ColorRuleCommandsTest {
    @Test
    fun realRoomRollsBackAnImportWhenTheSecondInsertFails() = runBlocking {
        val directory = Files.createTempDirectory("reader-rule-transaction").toFile()
        val database = Room.databaseBuilder<AppDatabase>(name = directory.resolve("rules.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            val real = database.readColorRuleDao
            val failing = object : ReadColorRuleDao by real {
                override suspend fun insert(rule: ReadColorRule): Long {
                    if (rule.keyword == "failure") throw IllegalStateException("injected storage failure")
                    return real.insert(rule)
                }
            }
            val owner = ColorRuleRepository(failing)
            val result = owner.applyImport(
                listOf(ReadColorRule(keyword = "first"), ReadColorRule(keyword = "failure")), "", false
            )
            assertTrue(result.isFailure)
            assertTrue(real.getAll().isEmpty())
            val saved = ColorRuleRepository(real).save(ReadColorRule(keyword = "retained")).getOrThrow()
            assertEquals(listOf(saved), real.getAll())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidRuleNeverWritesAndDatabaseFailureIsStructured() = runBlocking {
        val dao = MemoryRules()
        val owner = ColorRuleRepository(dao)
        assertTrue(owner.save(ReadColorRule()).isFailure)
        assertEquals(0, dao.rows.value.size)
        dao.failure = IllegalStateException("disk")
        assertEquals(ColorRuleError.STORAGE,
            (owner.save(ReadColorRule(keyword = "word")).exceptionOrNull() as ColorRuleException).reason)
    }

    @Test
    fun commandsPreserveOtherFieldsAndScopes() = runBlocking {
        val dao = MemoryRules()
        val owner = ColorRuleRepository(dao)
        val first = owner.save(ReadColorRule(keyword = "global", bold = true)).getOrThrow()
        val second = owner.save(ReadColorRule(keyword = "book", bookUrl = "book")).getOrThrow()
        owner.setEnabled(first.id, false).getOrThrow()
        assertEquals(first.copy(enabled = false), dao.rows.value.first())
        assertTrue(owner.reorder(listOf(second.id, first.id)).isFailure)
        owner.delete(first.id).getOrThrow()
        assertEquals(listOf(second), dao.rows.value)
    }

    @Test
    fun importDeduplicatesAndOnlyOverwritesColors() = runBlocking {
        val dao = MemoryRules()
        val owner = ColorRuleRepository(dao)
        val saved = owner.save(ReadColorRule(keyword = "word", foregroundColor = 1, bold = true)).getOrThrow()
        val incoming = saved.copy(id = 0, foregroundColor = 2, bold = false, enabled = false)
        assertEquals(ColorRuleImportStats(0, 0, 1), owner.applyImport(listOf(incoming), "book", false).getOrThrow())
        assertEquals(ColorRuleImportStats(0, 1, 0), owner.applyImport(listOf(incoming), "book", true).getOrThrow())
        assertEquals(saved.copy(foregroundColor = 2), dao.rows.value.single())
        val fresh = ReadColorRule(keyword = "new")
        assertEquals(ColorRuleImportStats(1, 0, 1), owner.applyImport(listOf(fresh, fresh), "book", false).getOrThrow())
    }

    @Test
    fun cancellationIsNotConvertedToFailure() = runBlocking {
        val dao = MemoryRules().apply { failure = CancellationException() }
        try {
            ColorRuleRepository(dao).save(ReadColorRule(keyword = "word"))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Coroutine ownership is retained by the caller.
        }
    }

    @Test
    fun moveAndExplicitOrderStayWithinScope() = runBlocking {
        val dao = MemoryRules()
        val owner = ColorRuleRepository(dao)
        val first = owner.save(ReadColorRule(keyword = "first")).getOrThrow()
        val second = owner.save(ReadColorRule(keyword = "second")).getOrThrow()
        val book = owner.save(ReadColorRule(keyword = "book", bookUrl = "book")).getOrThrow()
        owner.move(second.id, -1).getOrThrow()
        assertEquals(0, dao.rows.value.first { it.id == second.id }.sortOrder)
        assertEquals(1, dao.rows.value.first { it.id == first.id }.sortOrder)
        assertEquals(book, dao.rows.value.first { it.id == book.id })
        assertTrue(owner.reorder(listOf(first.id, first.id)).isFailure)
        assertTrue(owner.reorder(listOf(first.id)).isFailure)
        owner.reorder(listOf(first.id, second.id)).getOrThrow()
        assertEquals(0, dao.rows.value.first { it.id == first.id }.sortOrder)
    }

    @Test
    fun importRejectsForeignBooksAndLeavesExistingRowsUntouched() = runBlocking {
        val dao = MemoryRules()
        val owner = ColorRuleRepository(dao)
        assertEquals(ColorRuleImportStats(0, 0, 1),
            owner.applyImport(listOf(ReadColorRule(keyword = "foreign", bookUrl = "other")), "book", true).getOrThrow())
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun editorCancelDoesNotWriteAndSaveFailureRetainsInitialSelectionDraft() = runBlocking {
        val dao = MemoryRules()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val initial = ReadColorRule(bookUrl = "book", keyword = "selected", backgroundColor = 123)
            val model = ColorRuleScreenModel(scope, "book", initial, ColorRuleRepository(dao),
                onRulesChanged = { Result.success(Unit) })
            withTimeout(5000) { model.state.first { !it.loading } }
            assertEquals(initial, model.state.value.editor?.rule)
            model.dispatch(ColorRuleUiEvent.CancelEdit)
            assertTrue(dao.rows.value.isEmpty())
            assertNull(model.state.value.editor)
            model.dispatch(ColorRuleUiEvent.Edit(initial))
            dao.failure = IllegalStateException("disk")
            model.dispatch(ColorRuleUiEvent.Save)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals(ColorRuleError.STORAGE, model.state.value.error)
            assertEquals(initial, model.state.value.editor?.rule)
            dao.failure = null
            model.dispatch(ColorRuleUiEvent.Save)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertNull(model.state.value.editor)
            assertEquals("selected", dao.rows.value.single().keyword)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun exportCancellationDoesNotReportSuccessAndFailureIsVisible() = runBlocking {
        val dao = MemoryRules()
        val owner = ColorRuleRepository(dao)
        owner.save(ReadColorRule(keyword = "word")).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var result = Result.success(false)
            val model = ColorRuleScreenModel(scope, "", repository = owner, exportFile = { result },
                onRulesChanged = { Result.success(Unit) })
            model.dispatch(ColorRuleUiEvent.Export)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertFalse(model.state.value.exported)
            assertNull(model.state.value.error)
            result = Result.failure<Boolean>(ColorRuleException(ColorRuleError.FILE_IO))
            model.dispatch(ColorRuleUiEvent.Export)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals(ColorRuleError.FILE_IO, model.state.value.error)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun saveRefreshFailureRetainsPersistedIdAndRetryDoesNotInsertAgain() = runBlocking {
        val dao = MemoryRules()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var refreshes = 0
            val initial = ReadColorRule(bookUrl = "book", keyword = "selected", foregroundColor = 1)
            val model = ColorRuleScreenModel(scope, "book", initial, ColorRuleRepository(dao),
                onRulesChanged = {
                    refreshes++
                    if (refreshes == 1) Result.failure<Unit>(IllegalStateException("layout"))
                    else Result.success(Unit)
                })
            withTimeout(5000) { model.state.first { !it.loading } }
            model.dispatch(ColorRuleUiEvent.Save)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals(ColorRuleError.REFRESH, model.state.value.error)
            assertEquals(dao.rows.value.single().id, model.state.value.editor?.rule?.id)
            model.dispatch(ColorRuleUiEvent.Save)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals(1, dao.rows.value.size)
            assertEquals(2, refreshes)
            assertNull(model.state.value.editor)
        } finally { scope.cancel() }
    }

    @Test
    fun selectedKeywordEditsExistingRuleAndPreservesOtherChannels() = runBlocking {
        val dao = MemoryRules()
        val owner = ColorRuleRepository(dao)
        val saved = owner.save(ReadColorRule(keyword = "selected", bookUrl = "book",
            foregroundColor = 1, backgroundColor = 2, bold = true)).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = ColorRuleScreenModel(scope, "book",
                ReadColorRule(keyword = " selected ", bookUrl = "book", backgroundColor = 3), owner,
                onRulesChanged = { Result.success(Unit) })
            withTimeout(5000) { model.state.first { !it.loading } }
            assertEquals(saved.copy(backgroundColor = 3), model.state.value.editor?.rule)
            model.dispatch(ColorRuleUiEvent.Save)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals(listOf(saved.copy(backgroundColor = 3)), dao.rows.value)
        } finally { scope.cancel() }
    }

    @Test
    fun deleteRefreshRetryDoesNotDeleteANonexistentRow() = runBlocking {
        val dao = MemoryRules()
        val owner = ColorRuleRepository(dao)
        val saved = owner.save(ReadColorRule(keyword = "word")).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var refreshed = false
            val model = ColorRuleScreenModel(scope, "", repository = owner, onRulesChanged = {
                if (refreshed) Result.success(Unit)
                else Result.failure<Unit>(IllegalStateException("layout")).also { refreshed = true }
            })
            model.dispatch(ColorRuleUiEvent.AskDelete(saved))
            model.dispatch(ColorRuleUiEvent.Delete)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertEquals(ColorRuleError.REFRESH, model.state.value.error)
            assertNotNull(model.state.value.pendingDelete)
            model.dispatch(ColorRuleUiEvent.Delete)
            withTimeout(5000) { model.state.first { !it.busy } }
            assertNull(model.state.value.pendingDelete)
            assertNull(model.state.value.error)
        } finally { scope.cancel() }
    }
}

private class MemoryRules : ReadColorRuleDao {
    val rows = MutableStateFlow<List<ReadColorRule>>(emptyList())
    var failure: Exception? = null
    private var nextId = 1L
    override suspend fun getForBook(bookUrl: String) = getAll().filter { it.bookUrl.isEmpty() || it.bookUrl == bookUrl }
    override fun flowForBook(bookUrl: String): Flow<List<ReadColorRule>> =
        rows.map { all ->
            all.filter { it.bookUrl.isEmpty() || it.bookUrl == bookUrl }
                .sortedWith(compareByDescending<ReadColorRule> { it.bookUrl }.thenBy { it.sortOrder }.thenBy { it.id })
        }
    override suspend fun getAll(): List<ReadColorRule> {
        failure?.let { throw it }
        return rows.value
    }
    override suspend fun insert(rule: ReadColorRule): Long {
        failure?.let { throw it }
        val id = nextId++
        rows.value += rule.copy(id = id)
        return id
    }
    override suspend fun update(rule: ReadColorRule) {
        failure?.let { throw it }
        rows.value = rows.value.map { if (it.id == rule.id) rule else it }
    }
    override suspend fun updateAll(rules: List<ReadColorRule>) { rules.forEach { update(it) } }
    override suspend fun delete(rule: ReadColorRule) { rows.value -= rule }
}
