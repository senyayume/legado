package io.legado.app.help.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BackupAtomicWriteTest {
    @Test fun replacesExistingTextAndCreatesNewUtf8File() = inDirectory { directory ->
        val target = File(directory, "config.json")
        target.writeText("old")
        BackupFileOps.writeTextAtomically(target.path, "中文配置")
        assertEquals("中文配置", target.readText())
        val nested = File(directory, "nested/new.json")
        BackupFileOps.writeTextAtomically(nested.path, "新设置")
        assertEquals("新设置", nested.readText())
        assertFalse(directory.walk().any { it.name.endsWith(".tmp") })
    }

    @Test fun failedReplacementPreservesOriginalBytesAndRemovesTemporaryFile() = inDirectory { directory ->
        val target = File(directory, "config.json")
        val original = "旧配置与高亮".toByteArray()
        target.writeBytes(original)
        val result = runCatching {
            writeTextAtomicallyUsing(target.path, "new") { temporary, destination ->
                assertEquals(target.absolutePath, destination)
                assertEquals(directory.absolutePath, File(temporary).parent)
                assertEquals("new", File(temporary).readText())
                assertArrayEquals(original, target.readBytes())
                throw IOException("Replacement rejected")
            }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertArrayEquals(original, target.readBytes())
        assertEquals(listOf("config.json"), directory.listFiles()!!.map { it.name })
    }

    @Test fun realMoveFailureDoesNotDestroyDestinationDirectory() = inDirectory { directory ->
        val target = File(directory, "config.json").apply { mkdir() }
        val existing = File(target, "preserved").apply { writeText("keep") }
        assertTrue(runCatching { BackupFileOps.writeTextAtomically(target.path, "new") }.isFailure)
        assertEquals("keep", existing.readText())
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    private inline fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("reader-atomic-write-test-").toFile()
        try { block(directory) } finally { check(directory.deleteRecursively()) }
    }
}
