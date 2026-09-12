package io.legado.app.model.read

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.ReadColorRuleDao
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.ui.book.read.config.ColorRuleScreenModel
import io.legado.app.ui.book.read.config.ColorRuleUiEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ColorRuleScreenModelTest {
    @Test
    fun savedRuleRetainsEditorAndIdUntilRefreshRetrySucceeds() = withFixture {
        val initial = ReadColorRule(bookUrl = "book", keyword = "selected", backgroundColor = 7)
        val model = open(initial)
        model.dispatch(ColorRuleUiEvent.Save)

        val firstRefresh = refreshRequests.receive()
        val persisted = realDao.getAll().single()
        assertTrue(persisted.id > 0)
        assertEquals(persisted, model.state.value.editor?.rule)
        assertTrue(model.state.value.busy)
        assertTrue(model.state.value.needsRefresh)
        assertEquals(1, writes.inserts.get())

        // A stale dismiss callback cannot clear the editor while the write/refresh is active.
        model.dispatch(ColorRuleUiEvent.CancelEdit)
        assertNotNull(model.state.value.editor)
        failRefresh(model, firstRefresh)
        assertEquals(persisted.id, model.state.value.editor?.rule?.id)

        model.dispatch(ColorRuleUiEvent.Retry)
        val retry = refreshRequests.receive()
        assertEquals(listOf(persisted), realDao.getAll())
        assertEquals(1, writes.inserts.get())
        assertEquals(0, writes.updates.get())
        assertEquals(persisted.id, model.state.value.editor?.rule?.id)
        succeedRefresh(model, retry)
        assertNull(model.state.value.editor)
        assertEquals(1, writes.inserts.get())
        assertEquals(2, refreshCount.get())
    }

    @Test
    fun deleteRefreshRetryDoesNotDeleteAgainOrCloseConfirmationEarly() = withFixture {
        val rule = seed(ReadColorRule(bookUrl = "book", keyword = "delete"))
        val model = open()
        model.dispatch(ColorRuleUiEvent.AskDelete(rule))
        model.dispatch(ColorRuleUiEvent.Delete)

        val firstRefresh = refreshRequests.receive()
        assertTrue(realDao.getAll().isEmpty())
        assertEquals(rule, model.state.value.pendingDelete)
        model.dispatch(ColorRuleUiEvent.CancelDelete)
        assertEquals(rule, model.state.value.pendingDelete)
        failRefresh(model, firstRefresh)
        assertEquals(rule, model.state.value.pendingDelete)

        model.dispatch(ColorRuleUiEvent.Retry)
        val retry = refreshRequests.receive()
        assertEquals(1, writes.deletes.get())
        assertTrue(realDao.getAll().isEmpty())
        succeedRefresh(model, retry)
        assertNull(model.state.value.pendingDelete)
        assertEquals(1, writes.deletes.get())
        assertEquals(2, refreshCount.get())
    }

    @Test
    fun enableRefreshRetryDoesNotWriteAgainOrAcceptAnotherToggle() = withFixture {
        val rule = seed(ReadColorRule(bookUrl = "book", keyword = "toggle", enabled = true))
        val model = open()
        model.dispatch(ColorRuleUiEvent.Enable(rule.id, false))

        val firstRefresh = refreshRequests.receive()
        assertEquals(rule.copy(enabled = false), realDao.getAll().single())
        failRefresh(model, firstRefresh)
        model.dispatch(ColorRuleUiEvent.Enable(rule.id, true))
        assertEquals(ColorRuleError.REFRESH, model.state.value.error)
        assertFalse(model.state.value.busy)
        assertEquals(1, writes.updates.get())

        model.dispatch(ColorRuleUiEvent.Retry)
        val retry = refreshRequests.receive()
        assertEquals(1, writes.updates.get())
        succeedRefresh(model, retry)
        assertEquals(rule.copy(enabled = false), realDao.getAll().single())
        assertEquals(1, writes.updates.get())
        assertEquals(2, refreshCount.get())
    }

    @Test
    fun moveRefreshRetryDoesNotMoveTwiceAndPreservesOtherScope() = withFixture {
        val first = seed(ReadColorRule(bookUrl = "book", keyword = "first", sortOrder = 0))
        val second = seed(ReadColorRule(bookUrl = "book", keyword = "second", sortOrder = 1))
        val third = seed(ReadColorRule(bookUrl = "book", keyword = "third", sortOrder = 2))
        val global = seed(ReadColorRule(keyword = "global", sortOrder = 9))
        val model = open()
        model.dispatch(ColorRuleUiEvent.Move(third.id, -1))

        val firstRefresh = refreshRequests.receive()
        val afterMove = realDao.getAll()
        assertEquals(listOf(first.id, third.id, second.id),
            afterMove.filter { it.bookUrl == "book" }.map { it.id })
        assertEquals(global, afterMove.single { it.id == global.id })
        failRefresh(model, firstRefresh)
        model.dispatch(ColorRuleUiEvent.Move(third.id, -1))
        assertEquals(afterMove, realDao.getAll())
        assertEquals(1, writes.updateBatches.get())

        model.dispatch(ColorRuleUiEvent.Retry)
        val retry = refreshRequests.receive()
        assertEquals(afterMove, realDao.getAll())
        assertEquals(1, writes.updateBatches.get())
        succeedRefresh(model, retry)
        assertEquals(afterMove, realDao.getAll())
        assertEquals(1, writes.updateBatches.get())
        assertEquals(2, refreshCount.get())
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try {
            withTimeout(15_000) { fixture.block() }
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val queryJob = SupervisorJob()
        // These command/refresh tests need real SQL and isolated state, not disk lifetime.
        // File-backed close/reopen and upgrades are covered by ReaderDatabaseMigrationTest.
        private val database = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(queryJob + Dispatchers.IO)
            .build()
        val realDao = database.readColorRuleDao
        val writes = CountingDao(realDao)
        val refreshRequests = Channel<CompletableDeferred<Result<Unit>>>(Channel.RENDEZVOUS)
        val refreshCount = AtomicInteger()
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Default)
        private var model: ColorRuleScreenModel? = null

        suspend fun seed(rule: ReadColorRule): ReadColorRule =
            rule.copy(id = realDao.insert(rule))

        suspend fun open(initial: ReadColorRule? = null): ColorRuleScreenModel {
            val created = ColorRuleScreenModel(
                scope = scope,
                bookUrl = "book",
                initialRule = initial,
                repository = ColorRuleRepository(writes),
                onRulesChanged = {
                    refreshCount.incrementAndGet()
                    val completion = CompletableDeferred<Result<Unit>>()
                    refreshRequests.send(completion)
                    completion.await()
                },
            )
            model = created
            created.state.first { !it.loading }
            assertNull(created.state.value.error)
            return created
        }

        suspend fun failRefresh(
            model: ColorRuleScreenModel,
            completion: CompletableDeferred<Result<Unit>>,
        ) {
            completion.complete(Result.failure<Unit>(IllegalStateException("refresh fixture failure")))
            model.state.first { !it.busy }
            assertEquals(ColorRuleError.REFRESH, model.state.value.error)
            assertTrue(model.state.value.needsRefresh)
        }

        suspend fun succeedRefresh(
            model: ColorRuleScreenModel,
            completion: CompletableDeferred<Result<Unit>>,
        ) {
            completion.complete(Result.success(Unit))
            model.state.first { !it.busy }
            assertNull(model.state.value.error)
            assertFalse(model.state.value.needsRefresh)
        }

        suspend fun close() {
            model?.onCleared()
            job.cancel()
            job.join()
            refreshRequests.close()
            database.close()
            queryJob.cancelAndJoin()
            check(queryJob.isCompleted) { "Database query scope was not released" }
        }
    }

    /** Counts only the persistence boundary; every operation still runs against real Room. */
    private class CountingDao(private val actual: ReadColorRuleDao) : ReadColorRuleDao by actual {
        val inserts = AtomicInteger()
        val updates = AtomicInteger()
        val deletes = AtomicInteger()
        val updateBatches = AtomicInteger()

        override suspend fun insert(rule: ReadColorRule): Long {
            inserts.incrementAndGet()
            return actual.insert(rule)
        }

        override suspend fun update(rule: ReadColorRule) {
            updates.incrementAndGet()
            actual.update(rule)
        }

        override suspend fun delete(rule: ReadColorRule) {
            deletes.incrementAndGet()
            actual.delete(rule)
        }

        override suspend fun updateAll(rules: List<ReadColorRule>) {
            updateBatches.incrementAndGet()
            actual.updateAll(rules)
        }
    }
}
