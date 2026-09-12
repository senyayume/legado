package io.legado.app.help.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateAvailabilityTest {
    @Test
    fun unregisteredEnvironmentDoesNotInvokeAnyPlatformChecker() = runBlocking {
        val originals = UpdatePlatform.entries.associateWith(UpdateCheckers::of)
        var requests = 0
        val checker = object : UpdateChecker {
            override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
                requests++
                error("An unavailable update capability must not request a release")
            }
        }
        try {
            UpdatePlatform.entries.forEach { UpdateCheckers.register(it, checker) }
            assertFalse(AppUpdateManager.isAvailable())
            assertTrue(AppUpdateManager.check() is UpdateCheckResult.Failed)
            assertEquals(0, requests)
        } finally {
            originals.forEach { (platform, original) -> UpdateCheckers.register(platform, original) }
        }
    }
}
