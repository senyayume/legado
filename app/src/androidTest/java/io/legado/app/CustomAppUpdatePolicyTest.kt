package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.help.update.AppUpdateManager
import io.legado.app.help.update.UpdateChecker
import io.legado.app.help.update.UpdateCheckers
import io.legado.app.help.update.UpdateCheckRequest
import io.legado.app.help.update.UpdateCheckResult
import io.legado.app.help.update.UpdatePlatform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomAppUpdatePolicyTest {
    @Test
    fun customAndroidAppDoesNotOfferOrRequestUpstreamUpdates() = runBlocking {
        val original = UpdateCheckers.of(UpdatePlatform.ANDROID)
        var requests = 0
        UpdateCheckers.register(UpdatePlatform.ANDROID, object : UpdateChecker {
            override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
                requests++
                error("Custom Android builds must not request upstream releases")
            }
        })
        try {
            // The real Application startup determines capability availability.
            assertFalse(AppUpdateManager.isAvailable())
            assertTrue(AppUpdateManager.check() is UpdateCheckResult.Failed)
            assertEquals(0, requests)
        } finally {
            UpdateCheckers.register(UpdatePlatform.ANDROID, original)
        }
    }
}
