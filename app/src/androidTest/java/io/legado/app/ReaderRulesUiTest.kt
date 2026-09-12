package io.legado.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.model.read.ColorRuleError
import io.legado.app.model.read.ColorRuleRepository
import io.legado.app.ui.book.read.config.ColorRuleScreen
import io.legado.app.ui.book.read.config.ColorRuleScreenModel
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import legado.shared.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Rule commands use a separate Room file; MainActivity supplies the real platform host. */
@RunWith(AndroidJUnit4::class)
class ReaderRulesUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var file: File
    private lateinit var database: AppDatabase
    private lateinit var repository: ColorRuleRepository
    private lateinit var model: ColorRuleScreenModel
    private lateinit var labels: Map<StringResource, String>
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val bookUrl = "test://isolated-reader-rules"

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File.createTempFile("reader-rules-ui-", ".db", context.cacheDir)
        database = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repository = ColorRuleRepository(database.readColorRuleDao)
    }

    @After
    fun closeDatabase() {
        if (::model.isInitialized) compose.runOnIdle { model.onCleared() }
        runBlocking {
            job.cancel()
            job.join()
        }
        if (::database.isInitialized) database.close()
        if (::file.isInitialized) {
            // Only files belonging to this uniquely named test database are removed.
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val artifact = File(file.absolutePath + suffix)
                check(!artifact.exists() || artifact.delete()) { "Could not remove isolated Room file" }
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun createsKeywordPairAndRegexThroughEditorAndRendersNonblankPixels() {
        showRules()
        node(Res.string.reader_rules_empty).assertIsDisplayed()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        assertTrue(pixels.width > 0 && pixels.height > 0)
        val samples = buildSet {
            for (y in 0 until pixels.height step maxOf(1, pixels.height / 40)) {
                for (x in 0 until pixels.width step maxOf(1, pixels.width / 40)) add(pixels[x, y])
            }
        }
        assertTrue("Rules screen must contain painted content", samples.size > 1)

        addRule()
        field(Res.string.reader_rules_keyword).performTextReplacement("keyword-ui")
        saveAndAwait(1)

        addRule()
        chooseType(Res.string.reader_rules_pair)
        field(Res.string.reader_rules_left).performTextReplacement("[")
        field(Res.string.reader_rules_right).performTextReplacement("]")
        saveAndAwait(2)

        addRule()
        chooseType(Res.string.reader_rules_regex)
        field(Res.string.reader_rules_pattern).performTextReplacement("word[0-9]+")
        saveAndAwait(3)

        val rules = stored()
        assertEquals(listOf(ReadColorRule.TYPE_KEYWORD, ReadColorRule.TYPE_PAIR, ReadColorRule.TYPE_REGEX),
            rules.map { it.ruleType })
        assertEquals("keyword-ui", rules[0].keyword)
        assertEquals("[", rules[1].pairLeft)
        assertEquals("]", rules[1].pairRight)
        assertEquals("word[0-9]+", rules[2].pattern)
        assertTrue(rules.all { it.id > 0 && it.bookUrl == bookUrl })
    }

    @Test
    fun listTogglesMovesAndDeletesUsingRealOwner() {
        seed("first-ui")
        seed("second-ui")
        showRules()
        compose.onNode(isToggleable() and inRuleRow("first-ui"))
            .assertIsOn().performClick()
        compose.waitUntil(5_000) {
            !model.state.value.busy &&
                model.state.value.rules.firstOrNull { it.keyword == "first-ui" }?.enabled == false
        }
        assertFalse(stored().first { it.keyword == "first-ui" }.enabled)

        menuFor("second-ui")
        node(Res.string.reader_rules_move_up).performClick()
        compose.waitUntil(5_000) {
            !model.state.value.busy && model.state.value.rules.firstOrNull()?.keyword == "second-ui"
        }
        assertEquals(listOf("second-ui", "first-ui"), stored().map { it.keyword })

        menuFor("second-ui")
        node(Res.string.delete).performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        node(Res.string.yes).performClick()
        compose.waitUntil(5_000) {
            !model.state.value.busy && model.state.value.pendingDelete == null &&
                model.state.value.rules.size == 1
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals("first-ui", stored().single().keyword)
    }

    @Test
    fun invalidSaveAndFailedRefreshRetainEditorThenRetryKeepsPersistedId() {
        val refreshes = AtomicInteger()
        showRules {
            if (refreshes.incrementAndGet() == 1) {
                Result.failure<Unit>(IllegalStateException("isolated refresh failure"))
            } else Result.success(Unit)
        }
        addRule()
        chooseType(Res.string.reader_rules_regex)
        field(Res.string.reader_rules_pattern).performTextReplacement("[")
        saveButton().performClick()
        compose.waitUntil(5_000) {
            !model.state.value.busy && model.state.value.error == ColorRuleError.INVALID_RULE
        }
        node(Res.string.reader_rules_error_invalid).assertIsDisplayed()
        assertNotNull(model.state.value.editor)
        assertTrue(stored().isEmpty())
        assertEquals(0, refreshes.get())

        field(Res.string.reader_rules_pattern).performTextReplacement("valid[0-9]+")
        saveButton().performClick()
        compose.waitUntil(5_000) {
            !model.state.value.busy && model.state.value.error == ColorRuleError.REFRESH
        }
        node(Res.string.reader_rules_error_refresh).assertIsDisplayed()
        val persisted = stored().single()
        assertEquals(persisted.id, model.state.value.editor?.rule?.id)
        compose.onNodeWithText("valid[0-9]+").assertIsNotEnabled()

        node(Res.string.reader_rules_retry).performClick()
        compose.waitUntil(5_000) {
            !model.state.value.busy && model.state.value.editor == null &&
                !model.state.value.needsRefresh
        }
        assertEquals(listOf(persisted), stored())
        assertEquals(2, refreshes.get())
    }

    private fun showRules(onRefresh: suspend () -> Result<Unit> = { Result.success(Unit) }) {
        model = ColorRuleScreenModel(scope, bookUrl, repository = repository, onRulesChanged = onRefresh)
        setReaderContent {
            labels = listOf(
                Res.string.create, Res.string.action_save, Res.string.more_menu,
                Res.string.delete, Res.string.yes,
                Res.string.reader_rules_empty, Res.string.reader_rules_keyword,
                Res.string.reader_rules_pair, Res.string.reader_rules_regex,
                Res.string.reader_rules_type,
                Res.string.reader_rules_left, Res.string.reader_rules_right,
                Res.string.reader_rules_pattern, Res.string.reader_rules_move_up,
                Res.string.reader_rules_error_invalid, Res.string.reader_rules_error_refresh,
                Res.string.reader_rules_retry,
            ).associateWith { stringResource(it) }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides remember { AndroidThemeStoreProvider() },
                LocalAppConfigProvider provides remember { AndroidAppConfigProvider() },
                LocalEventBusProvider provides remember { AndroidEventBusProvider() },
            ) {
                AppTheme {
                    val state by model.state.collectAsState()
                    ColorRuleScreen(state, model::dispatch, onBack = {})
                }
            }
        }
        compose.waitUntil(5_000) { !model.state.value.loading }
        compose.waitForIdle()
        assertNull(model.state.value.error)
    }

    private fun node(resource: StringResource) = compose.onNodeWithText(labels.getValue(resource))

    private fun setReaderContent(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent(content = content) }
    }

    private fun field(resource: StringResource) = compose.onNode(
        hasSetTextAction() and (
            hasText(labels.getValue(resource)) or hasAnyDescendant(hasText(labels.getValue(resource)))
        ),
    ).performScrollTo()

    private fun addRule() {
        compose.onNodeWithContentDescription(labels.getValue(Res.string.create)).performClick()
        compose.waitUntil(5_000) { model.state.value.editor != null }
    }

    private fun chooseType(type: StringResource) {
        compose.onNodeWithContentDescription(labels.getValue(Res.string.reader_rules_type))
            .performScrollTo().performClick()
        node(type).performClick()
    }

    private fun saveButton() =
        compose.onNodeWithContentDescription(labels.getValue(Res.string.action_save))

    private fun saveAndAwait(count: Int) {
        saveButton().performClick()
        compose.waitUntil(5_000) {
            !model.state.value.busy && model.state.value.editor == null &&
                model.state.value.rules.size == count
        }
        assertNull(model.state.value.error)
    }

    private fun inRuleRow(keyword: String) = hasAnyAncestor(hasAnyChild(hasText(keyword)))

    private fun menuFor(keyword: String) {
        compose.onNode(hasContentDescription(labels.getValue(Res.string.more_menu)) and inRuleRow(keyword))
            .performScrollTo().performClick()
    }

    private fun seed(keyword: String) = runBlocking {
        repository.save(ReadColorRule(bookUrl = bookUrl, keyword = keyword)).getOrThrow()
    }

    private fun stored() = runBlocking { database.readColorRuleDao.getForBook(bookUrl) }
}
