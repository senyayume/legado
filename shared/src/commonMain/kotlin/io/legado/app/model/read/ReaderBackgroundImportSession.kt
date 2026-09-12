package io.legado.app.model.read

import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.help.FileUtilsCommon
import io.legado.app.utils.Md5Digest
import io.legado.app.utils.toHexLower
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

enum class ReaderBackgroundImportError { UNSUPPORTED_FORMAT, TOO_LARGE, FILE_IO, CLOSED, LIMIT_REACHED }

class ReaderBackgroundImportException(
    val reason: ReaderBackgroundImportError,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

/** File ownership belongs to the editing session until the appearance is saved. */
class ReaderBackgroundImportSession internal constructor(private val files: Files) {
    constructor() : this(LocalFiles)

    internal interface Files {
        fun size(path: String): Long
        fun read(path: String, limit: Int): ByteArray
        fun path(name: String): String
        fun exists(path: String): Boolean
        fun write(path: String, bytes: ByteArray)
        fun delete(path: String): Boolean
        fun references(): Set<String>
    }

    private val created = linkedSetOf<String>()
    private val lock = SynchronizedObject()
    private var closed = false
    private var closing = false
    private var saving = false
    private var leaveRequested = false

    fun import(path: String): Result<String> = checked {
        synchronized(lock) {
            ensureOpen()
            if (created.size >= MAX_FILES) fail(ReaderBackgroundImportError.LIMIT_REACHED)
        }
        val suffix = path.substringAfterLast('.', "").lowercase()
        if (suffix !in setOf("png", "jpg", "jpeg", "webp")) {
            fail(ReaderBackgroundImportError.UNSUPPORTED_FORMAT)
        }
        val length = files.size(path)
        if (length > MAX_BYTES) fail(ReaderBackgroundImportError.TOO_LARGE)
        if (length <= 0) fail(ReaderBackgroundImportError.FILE_IO)
        val bytes = files.read(path, MAX_BYTES)
        if (bytes.isEmpty()) fail(ReaderBackgroundImportError.FILE_IO)
        if (bytes.size > MAX_BYTES) fail(ReaderBackgroundImportError.TOO_LARGE)
        val digest = Md5Digest()
        digest.update(bytes, 0, bytes.size)
        val name = digest.digest().toHexLower() + ".$suffix"
        val destination = files.path(name)
        synchronized(lock) {
            ensureOpen()
            if (!files.exists(destination)) {
                if (created.size >= MAX_FILES) fail(ReaderBackgroundImportError.LIMIT_REACHED)
                // Record before writing so cancel also removes an interrupted/partial write.
                created += destination
                files.write(destination, bytes)
            }
        }
        name
    }

    fun cancel(): Result<Unit> = synchronized(lock) {
        if (closed) {
            Result.success(Unit)
        } else if (saving) {
            leaveRequested = true
            Result.success(Unit)
        } else finish(emptySet())
    }

    /** Coordinate the local persistence transaction with route disposal and image ownership. */
    suspend fun apply(
        draft: ReadStyleConfig,
        persist: suspend () -> Result<Unit>,
    ): Result<Unit> {
        val started = checked {
            synchronized(lock) {
                ensureOpen()
                saving = true
            }
        }
        if (started.isFailure) return started
        return withContext(NonCancellable) {
            var saved = false
            try {
                val result = persist()
                saved = result.isSuccess
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(ReaderBackgroundImportException(ReaderBackgroundImportError.FILE_IO, failure))
            } finally {
                synchronized(lock) {
                    saving = false
                    val cleanup = if (saved) {
                        commit(draft)
                    } else if (leaveRequested) {
                        cancel()
                    } else Result.success(Unit)
                    cleanup.onFailure { AppLog.put("Background cleanup failed", it) }
                }
            }
        }
    }

    /** Call only after the draft was successfully persisted. */
    fun commit(draft: ReadStyleConfig): Result<Unit> =
        finish((0..2).mapNotNull { draft.getBgPath(it) }.toSet())

    internal fun finish(retained: Set<String>): Result<Unit> = checked {
        synchronized(lock) {
            if (closed) fail(ReaderBackgroundImportError.CLOSED)
            closing = true
            val protected = (files.references() + retained).map(::normalize).toSet()
            for (path in created.toList()) {
                if (normalize(path) in protected || !files.exists(path) || files.delete(path)) {
                    created.remove(path)
                } else {
                    fail(ReaderBackgroundImportError.FILE_IO)
                }
            }
            closed = true
        }
    }

    private fun ensureOpen() { if (closed || closing || saving) fail(ReaderBackgroundImportError.CLOSED) }

    private inline fun <T> checked(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ReaderBackgroundImportException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(ReaderBackgroundImportException(ReaderBackgroundImportError.FILE_IO, e))
    }

    private object LocalFiles : Files {
        override fun size(path: String) = BackupFileOps.fileSize(path)
        override fun read(path: String, limit: Int): ByteArray {
            val input = BackupFileOps.openInputStream(path)
            try {
                val bytes = ByteArray(limit + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    if (read == 0) fail(ReaderBackgroundImportError.FILE_IO)
                    count += read
                }
                if (count > limit) fail(ReaderBackgroundImportError.TOO_LARGE)
                return bytes.copyOf(count)
            } finally { input.close() }
        }
        override fun path(name: String) = ReadStyleConfig(bgType = 2, bgStr = name).getBgPath(0)
            ?: fail(ReaderBackgroundImportError.FILE_IO)
        override fun exists(path: String) = BackupFileOps.exists(path)
        override fun write(path: String, bytes: ByteArray) {
            BackupFileOps.createFolderIfNotExist(path.substringBeforeLast(BackupFileOps.separator))
            if (!FileUtilsCommon.writeBytes(path, bytes)) fail(ReaderBackgroundImportError.FILE_IO)
        }
        override fun delete(path: String) = BackupFileOps.delete(path)
        override fun references(): Set<String> =
            (ReadBookConfigProviders.get().configList + ReadBookConfigProviders.get().shareConfig)
                .flatMap { config -> (0..2).mapNotNull { config.getBgPath(it) } }.toSet()
    }

    companion object {
        const val MAX_BYTES = 12 * 1024 * 1024
        const val MAX_FILES = 32
        private fun normalize(path: String) = path.replace('\\', '/')
        private fun fail(reason: ReaderBackgroundImportError): Nothing =
            throw ReaderBackgroundImportException(reason)
    }
}
