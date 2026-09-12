package io.legado.app.model.read

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import io.legado.app.help.config.ReadStyleConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ReaderBackgroundImportSessionTest {
    private class Files : ReaderBackgroundImportSession.Files {
        val saved = mutableMapOf<String, ByteArray>()
        var source = byteArrayOf(1, 2, 3)
        var declaredSize = source.size.toLong()
        var reads = 0
        var failDelete = false
        var failWrite = false
        var onRead: () -> Unit = {}
        val referenced = mutableSetOf<String>()
        override fun size(path: String) = declaredSize
        override fun read(path: String, limit: Int): ByteArray { reads++; onRead(); return source }
        override fun path(name: String) = "/backgrounds/$name"
        override fun exists(path: String) = saved.containsKey(path)
        override fun write(path: String, bytes: ByteArray) {
            saved[path] = bytes
            if (failWrite) throw IllegalStateException("interrupted")
        }
        override fun delete(path: String): Boolean {
            if (failDelete) return false
            saved.remove(path)
            return true
        }
        override fun references() = referenced.toSet()
    }

    @Test fun rejectsOversizeAndUnsupportedFilesBeforeReading() {
        val files = Files()
        val session = ReaderBackgroundImportSession(files)
        assertReason(ReaderBackgroundImportError.UNSUPPORTED_FORMAT, session.import("image.svg"))
        files.declaredSize = ReaderBackgroundImportSession.MAX_BYTES + 1L
        assertReason(ReaderBackgroundImportError.TOO_LARGE, session.import("image.png"))
        assertEquals(0, files.reads)
        assertTrue(files.saved.isEmpty())
    }

    @Test fun cancellationDeletesOnlySessionOwnedUnreferencedImages() {
        val files = Files()
        val first = ReaderBackgroundImportSession(files)
        val existing = files.path(first.import("image.png").getOrThrow())
        first.finish(setOf(existing)).getOrThrow()
        val session = ReaderBackgroundImportSession(files)
        session.import("image.png").getOrThrow()
        files.source = byteArrayOf(4)
        val referenced = files.path(session.import("other.jpg").getOrThrow())
        files.referenced += referenced
        files.source = byteArrayOf(5)
        val unused = files.path(session.import("unused.webp").getOrThrow())
        session.cancel().getOrThrow()
        assertTrue(files.exists(existing))
        assertTrue(files.exists(referenced))
        assertFalse(files.exists(unused))
        assertReason(ReaderBackgroundImportError.CLOSED, session.import("image.png"))
    }

    @Test fun commitRetainsSelectedImageAndDeletesUnusedImports() {
        val files = Files()
        val session = ReaderBackgroundImportSession(files)
        val selected = files.path(session.import("image.PNG").getOrThrow())
        files.source = byteArrayOf(9)
        val unused = files.path(session.import("other.png").getOrThrow())
        session.finish(setOf(selected)).getOrThrow()
        assertEquals(setOf(selected), files.saved.keys)
        assertFalse(files.exists(unused))
    }

    @Test fun failedCleanupCanBeRetriedAndPartialWriteIsRemoved() {
        val files = Files()
        val session = ReaderBackgroundImportSession(files)
        files.failWrite = true
        assertReason(ReaderBackgroundImportError.FILE_IO, session.import("image.jpg"))
        assertEquals(1, files.saved.size)
        files.failDelete = true
        assertReason(ReaderBackgroundImportError.FILE_IO, session.cancel())
        assertReason(ReaderBackgroundImportError.CLOSED, session.import("image.jpg"))
        files.failDelete = false
        session.cancel().getOrThrow()
        assertTrue(files.saved.isEmpty())
    }

    @Test fun cancelDuringReadDoesNotWaitAndPreventsLateWrite() {
        val files = Files()
        val session = ReaderBackgroundImportSession(files)
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        files.onRead = {
            reading.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Result<String>> { session.import("image.png") }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            session.cancel().getOrThrow()
            resume.countDown()
            assertReason(ReaderBackgroundImportError.CLOSED, result.get(5, TimeUnit.SECONDS))
            assertTrue(files.saved.isEmpty())
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun leaveDuringSuccessfulSaveKeepsThePersistedImage() = runBlocking {
        val files = Files()
        val session = ReaderBackgroundImportSession(files)
        val image = files.path(session.import("image.png").getOrThrow())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val job = launch {
            session.apply(ReadStyleConfig()) {
                entered.complete(Unit)
                resume.await()
                files.referenced += image
                Result.success(Unit)
            }.getOrThrow()
        }
        entered.await()
        session.cancel().getOrThrow()
        assertTrue(files.exists(image))
        resume.complete(Unit)
        job.join()
        assertTrue(files.exists(image))
    }

    @Test fun leaveDuringFailedSaveRemovesUncommittedImage() = runBlocking {
        val files = Files()
        val session = ReaderBackgroundImportSession(files)
        val image = files.path(session.import("image.png").getOrThrow())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val job = launch {
            val result = session.apply(ReadStyleConfig()) {
                entered.complete(Unit)
                resume.await()
                Result.failure(IllegalStateException("write failed"))
            }
            assertTrue(result.isFailure)
        }
        entered.await()
        session.cancel().getOrThrow()
        assertTrue(files.exists(image))
        resume.complete(Unit)
        job.join()
        assertFalse(files.exists(image))
    }

    @Test fun cleanupFailureAfterSuccessfulSaveDoesNotChangeSaveResult() = runBlocking {
        val files = Files()
        val session = ReaderBackgroundImportSession(files)
        session.import("unused.png").getOrThrow()
        files.failDelete = true
        assertTrue(session.apply(ReadStyleConfig()) { Result.success(Unit) }.isSuccess)
        assertEquals(1, files.saved.size)
        files.failDelete = false
        session.cancel().getOrThrow()
        session.cancel().getOrThrow()
        assertTrue(files.saved.isEmpty())
    }

    private fun assertReason(reason: ReaderBackgroundImportError, result: Result<*>) {
        assertEquals(reason, (result.exceptionOrNull() as ReaderBackgroundImportException).reason)
    }
}
