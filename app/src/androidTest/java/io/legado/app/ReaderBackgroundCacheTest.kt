package io.legado.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.ui.book.read.page.ReaderBackgroundImageCache
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real Android file decoder and asynchronous cache, with a unique local source. */
@RunWith(AndroidJUnit4::class)
class ReaderBackgroundCacheTest {
    @Test
    fun retryDuringFailureCooldownDoesNotBlockExplicitRetryAfterFileIsRepaired() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("reader-background-retry-", ".png", context.cacheDir)
        val source = file.absolutePath
        try {
            file.writeText("invalid image", Charsets.UTF_8)
            ReaderBackgroundImageCache.requestAsync(source)
            assertTrue("The real decoder must report the invalid source before retrying", await {
                ReaderBackgroundImageCache.isFailed(source)
            })

            // A render or re-selection during cooldown must not reserve an in-flight load.
            ReaderBackgroundImageCache.requestAsync(source)
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.MAGENTA)
                file.outputStream().use { output ->
                    assertTrue("PNG fixture must be encoded successfully",
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }

            ReaderBackgroundImageCache.clearFailed(source)
            ReaderBackgroundImageCache.requestAsync(source)
            assertTrue("Explicit retry must decode the repaired file instead of remaining in-flight forever", await {
                ReaderBackgroundImageCache.peek(source) != null
            })
            val loaded = checkNotNull(ReaderBackgroundImageCache.peek(source))
            assertEquals(4, loaded.width)
            assertEquals(4, loaded.height)
            assertEquals(Color.Magenta, loaded.toPixelMap()[0, 0])
            assertFalse(ReaderBackgroundImageCache.isFailed(source))
        } finally {
            ReaderBackgroundImageCache.clearFailed(source)
            assertTrue("Only the isolated source file is removed", !file.exists() || file.delete())
        }
    }

    private suspend fun await(condition: () -> Boolean): Boolean = withTimeoutOrNull(5_000L) {
        while (!condition()) delay(10L)
        true
    } ?: false
}
