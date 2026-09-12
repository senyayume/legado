package io.legado.app.help.config

import io.legado.app.help.file.AppFilesDir
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.model.read.ReaderPaletteSet
import io.legado.app.model.read.ReaderPaletteMode
import io.legado.app.utils.KS_JSON
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPalettePersistenceTest {

    @Test
    fun bundledFirstPresetAndMissingFieldsUseUserReadingTemplate() {
        assertUserTemplate(ReadStyleConfig())
        assertUserTemplate(ReadConfigDefaults.readConfigs.first())
        assertUserTemplate(KS_JSON.decodeFromString(ReadStyleConfig.serializer(), "{}"))
        ReadConfigDefaults.readConfigs.forEach {
            assertEquals(21, it.textSize)
            assertEquals(0.06f, it.letterSpacing)
            assertEquals(io.legado.app.constant.PageAnim.slidePageAnim, it.pageAnim)
        }
    }

    @Test
    fun missingConfigurationAndRestoredFirstPresetUseUserTemplate() {
        assertTrue(File(owner.configFilePath).delete())
        assertTrue(File(owner.shareConfigFilePath).delete())
        val fresh = ReadBookConfigShared(TestPreferences())
        assertUserTemplate(fresh.config)
        fresh.durConfig = fresh.durConfig.copy(textSize = 39, bgStr = "#abcdef")
        // 与预设恢复入口相同：复制唯一内置模板，不修改进程级默认实例。
        fresh.durConfig = ReadConfigDefaults.readConfigs.first().copy()
        assertUserTemplate(fresh.config)
    }

    @Test
    fun persistedExplicitUserTypographyAndBackgroundRemainUntouched() {
        File(owner.configFilePath).writeText(
            KS_JSON.encodeToString(ListSerializer(ReadStyleConfig.serializer()),
                List(6) { ReadStyleConfig(textSize = 37, bgStr = "#123456", pageAnim = 0) }))
        val reopened = ReadBookConfigShared(TestPreferences())
        assertEquals(37, reopened.config.textSize)
        assertEquals("#123456", reopened.config.bgStr)
        assertEquals(0, reopened.config.pageAnim)
    }

    private fun assertUserTemplate(config: ReadStyleConfig) {
        assertEquals("#DCD9C6", config.bgStr)
        assertEquals(0, config.bgType)
        assertEquals("#3E3D3B", config.textColorStr)
        assertEquals(21, config.textSize)
        assertEquals(0.06f, config.letterSpacing)
        assertEquals(13, config.lineSpacingExtra)
        assertEquals(5, config.paragraphSpacing)
        assertEquals(io.legado.app.constant.PageAnim.slidePageAnim, config.pageAnim)
        assertEquals(listOf(25, 25, 25, 25),
            listOf(config.paddingLeft, config.paddingTop, config.paddingRight, config.paddingBottom))
        assertEquals(listOf(0, 2, 24, 0),
            listOf(config.titleMode, config.titleSize, config.titleTopSpacing, config.titleBottomSpacing))
        assertEquals(listOf(16, 0, 16, 0), listOf(config.headerPaddingLeft,
            config.headerPaddingTop, config.headerPaddingRight, config.headerPaddingBottom))
        assertEquals(listOf(16, 8, 16, 6), listOf(config.footerPaddingLeft,
            config.footerPaddingTop, config.footerPaddingRight, config.footerPaddingBottom))
        assertFalse(config.showHeaderLine)
        assertFalse(config.showFooterLine)
        assertEquals(0, config.tipColor)
        assertEquals(listOf(2, 0, 3, 1, 0, 6), listOf(config.tipHeaderLeft,
            config.tipHeaderMiddle, config.tipHeaderRight, config.tipFooterLeft,
            config.tipFooterMiddle, config.tipFooterRight))
    }

    @Test
    fun missingPaletteToggleEnablesColorsButExplicitOptOutSurvives() {
        assertTrue(ReadStyleConfig().readerPaletteEnabled)
        assertTrue(KS_JSON.decodeFromString(ReadStyleConfig.serializer(), "{}").readerPaletteEnabled)
        assertFalse(KS_JSON.decodeFromString(ReadStyleConfig.serializer(), "{\"readerPaletteEnabled\":false}").readerPaletteEnabled)
    }

    @Test
    fun appearanceCommitPersistsBackgroundAndBodyWithoutReplacingTypography() = runBlocking {
        val target = owner.config
        target.textSize = 31
        val appearance = target.copy(bgAlpha = 70, bgTypeNight = 2, bgStrNight = "/tmp/paper.png", textSize = 99)
        appearance.setTextColorForMode(ReaderPaletteMode.DAY, 0xff123456.toInt())
        assertTrue(owner.applyReaderPalette(target, appearance).isSuccess)
        assertEquals(31, target.textSize)
        assertEquals(70, target.bgAlpha)
        assertEquals("/tmp/paper.png", target.bgStrNight)
        assertEquals(0xff123456.toInt(), target.textColorForMode(ReaderPaletteMode.DAY))
        val reloaded = ReadBookConfigShared(preferences).config
        assertEquals(31, reloaded.textSize)
        assertEquals(70, reloaded.bgAlpha)
        assertEquals("/tmp/paper.png", reloaded.bgStrNight)
        assertEquals(0xff123456.toInt(), reloaded.textColorForMode(ReaderPaletteMode.DAY))
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var previousDirectories: AppFilesDir? = null
    private lateinit var owner: ReadBookConfigShared
    private lateinit var preferences: PreferenceProvider

    @Before
    fun setUp() {
        previousDirectories = runCatching { AppFilesDirs.get() }.getOrNull()
        val root = temporaryFolder.root.absolutePath
        AppFilesDirs.register(object : AppFilesDir {
            override val filesDir = root
            override val cacheDir = root
            override val externalFilesDir: String? = null
            override val externalCacheDir: String? = null
        })
        // Seed a complete list so initialization never schedules a reset/save.
        File(root, ReadBookConfigShared.configFileName).writeText(
            KS_JSON.encodeToString(
                ListSerializer(ReadStyleConfig.serializer()),
                List(6) { ReadStyleConfig(name = "style-$it", readerPaletteEnabled = false) },
            ),
        )
        File(root, ReadBookConfigShared.shareConfigFileName).writeText(
            KS_JSON.encodeToString(ReadStyleConfig.serializer(), ReadStyleConfig(readerPaletteEnabled = false))
        )
        preferences = TestPreferences()
        owner = ReadBookConfigShared(preferences)
        owner.readStyleSelect = 2
    }

    @After
    fun tearDown() {
        val previous = previousDirectories
        if (previous == null) AppFilesDirs.reset() else AppFilesDirs.register(previous)
    }

    @Test
    fun successfulApplyHasPersistedCurrentStyleBeforeReturning() = runBlocking {
        val target = owner.durConfig
        val palettes = ReaderPaletteSet().apply {
            day.numberColor = 101
            night.numberColor = 202
            eInk.numberColor = 303
        }
        val untouchedStyle = owner.configList[0].copy()

        val result = owner.applyReaderPalette(target, target.copy(readerPalette = palettes))

        assertTrue(result.toString(), result.isSuccess)
        assertTrue(target.readerPaletteEnabled)
        assertEquals(palettes, target.readerPalette)
        val persisted = KS_JSON.decodeFromString(
            ListSerializer(ReadStyleConfig.serializer()),
            File(owner.configFilePath).readText(),
        )
        assertTrue(persisted[2].readerPaletteEnabled)
        assertEquals(palettes, persisted[2].readerPalette)
        assertEquals(untouchedStyle, persisted[0])
        val reloaded = ReadBookConfigShared(preferences).durConfig
        assertTrue(reloaded.readerPaletteEnabled)
        assertEquals(palettes, reloaded.readerPalette)
    }

    @Test
    fun writeFailurePreservesOriginalPaletteAndEnabledFlag() = runBlocking {
        val target = owner.durConfig
        val original = target.readerPalette
        val path = File(owner.configFilePath)
        assertTrue(path.delete())
        assertTrue(path.mkdir())
        File(path, "keep").writeText("not a writable config file")

        val result = owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))

        assertTrue(result.isFailure)
        assertEquals(
            ReaderPaletteApplyFailure.WRITE_FAILED,
            (result.exceptionOrNull() as ReaderPaletteApplyException).reason,
        )
        assertSame(original, target.readerPalette)
        assertFalse(target.readerPaletteEnabled)
        assertEquals("not a writable config file", File(path, "keep").readText())
    }

    @Test
    fun detachedEqualTargetIsRejectedWithoutWriting() = runBlocking {
        val target = owner.durConfig.copy()
        val before = File(owner.configFilePath).readText()

        assertTrue(owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet())).isFailure)

        assertFalse(target.readerPaletteEnabled)
        assertEquals(before, File(owner.configFilePath).readText())
    }

    @Test
    fun previouslySelectedTargetIsRejectedWithoutWriting() = runBlocking {
        val target = owner.durConfig
        owner.readStyleSelect = 3
        val before = File(owner.configFilePath).readText()

        assertTrue(owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet())).isFailure)

        assertFalse(target.readerPaletteEnabled)
        assertEquals(before, File(owner.configFilePath).readText())
    }

    @Test
    fun sharedLayoutPersistsActivePaletteOnlyToSharedConfigFile() = runBlocking {
        owner.shareLayout = true
        val target = owner.config
        val before = File(owner.configFilePath).readText()
        val palettes = ReaderPaletteSet().apply { night.numberColor = 321 }

        assertTrue(owner.applyReaderPalette(target, target.copy(readerPalette = palettes)).isSuccess)

        assertSame(owner.shareConfig, target)
        assertTrue(target.readerPaletteEnabled)
        assertEquals(palettes, target.readerPalette)
        val persisted = KS_JSON.decodeFromString(
            ReadStyleConfig.serializer(), File(owner.shareConfigFilePath).readText(),
        )
        assertTrue(persisted.readerPaletteEnabled)
        assertEquals(palettes, persisted.readerPalette)
        assertTrue(ReadBookConfigShared(preferences).config.readerPaletteEnabled)
        assertEquals(palettes, ReadBookConfigShared(preferences).config.readerPalette)
        assertEquals(before, File(owner.configFilePath).readText())
        assertFalse(owner.durConfig.readerPaletteEnabled)
        palettes.night.numberColor = 654
        assertEquals(321, target.readerPalette.night.numberColor)
    }

    @Test
    fun sharedLayoutRejectsInactiveDurConfig() = runBlocking {
        owner.shareLayout = true
        val target = owner.durConfig
        val before = File(owner.configFilePath).readText()

        assertTrue(owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet())).isFailure)

        assertFalse(target.readerPaletteEnabled)
        assertEquals(before, File(owner.configFilePath).readText())
        assertEquals(KS_JSON.encodeToString(ReadStyleConfig.serializer(), ReadStyleConfig(readerPaletteEnabled = false)), File(owner.shareConfigFilePath).readText())
    }

    @Test
    fun sharedWriteFailurePreservesActivePaletteAndThemeFile() = runBlocking {
        owner.shareLayout = true
        val target = owner.config
        target.readerPaletteEnabled = true
        val original = target.readerPalette
        val before = File(owner.configFilePath).readText()
        val path = File(owner.shareConfigFilePath)
        assertTrue(path.delete())
        assertTrue(path.mkdir())
        File(path, "keep").writeText("not a writable config file")

        val result = owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))

        assertTrue(result.isFailure)
        assertEquals(
            ReaderPaletteApplyFailure.WRITE_FAILED,
            (result.exceptionOrNull() as ReaderPaletteApplyException).reason,
        )
        assertSame(original, target.readerPalette)
        assertTrue(target.readerPaletteEnabled)
        assertEquals(before, File(owner.configFilePath).readText())
        assertEquals("not a writable config file", File(path, "keep").readText())
    }

    @Test
    fun sharedTargetReplacementWhileWaitingForIoIsRejected() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        owner.shareLayout = true
        val target = owner.config
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))
        }
        owner.shareConfig = target.copy()
        dispatcher.runNext()

        assertTrue(pending.await().isFailure)
        assertFalse(target.readerPaletteEnabled)
        assertFalse(owner.config.readerPaletteEnabled)
        assertEquals(KS_JSON.encodeToString(ReadStyleConfig.serializer(), ReadStyleConfig(readerPaletteEnabled = false)), File(owner.shareConfigFilePath).readText())
    }

    @Test
    fun sharedTargetMutationWhileWaitingForIoIsRejected() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        owner.shareLayout = true
        val target = owner.config
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))
        }
        target.readerPalette.night.numberColor = 617
        dispatcher.runNext()

        assertTrue(pending.await().isFailure)
        assertEquals(617, target.readerPalette.night.numberColor)
        assertFalse(target.readerPaletteEnabled)
        assertEquals(KS_JSON.encodeToString(ReadStyleConfig.serializer(), ReadStyleConfig(readerPaletteEnabled = false)), File(owner.shareConfigFilePath).readText())
    }

    @Test
    fun sharingChangeWhileWaitingForIoIsRejectedEvenWhenTargetIsAliased() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        owner.shareLayout = true
        owner.shareConfig = owner.durConfig
        val target = owner.config
        val before = File(owner.configFilePath).readText()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))
        }
        owner.shareLayout = false
        assertSame(target, owner.config)
        dispatcher.runNext()

        assertTrue(pending.await().isFailure)
        assertFalse(target.readerPaletteEnabled)
        assertEquals(before, File(owner.configFilePath).readText())
        assertEquals(KS_JSON.encodeToString(ReadStyleConfig.serializer(), ReadStyleConfig(readerPaletteEnabled = false)), File(owner.shareConfigFilePath).readText())
    }

    @Test
    fun callerMutationsCannotChangePendingOrAppliedPalette() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        val target = owner.durConfig
        val palettes = ReaderPaletteSet()
        val expected = palettes.copy(
            day = palettes.day.copy(), night = palettes.night.copy(), eInk = palettes.eInk.copy(),
        )
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = palettes))
        }
        palettes.day.numberColor = 401
        palettes.night.numberColor = 402
        palettes.eInk.numberColor = 403
        assertFalse(target.readerPaletteEnabled)
        dispatcher.runNext()

        assertTrue(pending.await().isSuccess)
        assertEquals(expected, target.readerPalette)
        assertNotSame(palettes, target.readerPalette)
        assertNotSame(palettes.day, target.readerPalette.day)
        assertNotSame(palettes.night, target.readerPalette.night)
        assertNotSame(palettes.eInk, target.readerPalette.eInk)
        palettes.day.numberColor = 501
        assertEquals(expected, target.readerPalette)
        assertEquals(expected, ReadBookConfigShared(preferences).durConfig.readerPalette)
    }

    @Test
    fun targetMutationWhileWaitingForIoIsRejected() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        val target = owner.durConfig
        val before = File(owner.configFilePath).readText()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))
        }
        target.readerPalette.day.numberColor = 601
        dispatcher.runNext()

        assertTrue(pending.await().isFailure)
        assertEquals(601, target.readerPalette.day.numberColor)
        assertFalse(target.readerPaletteEnabled)
        assertEquals(before, File(owner.configFilePath).readText())
    }

    @Test
    fun replacedTargetWhileWaitingForIoIsRejected() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        val target = owner.durConfig
        val before = File(owner.configFilePath).readText()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))
        }
        owner.durConfig = target.copy()
        dispatcher.runNext()

        assertTrue(pending.await().isFailure)
        assertFalse(target.readerPaletteEnabled)
        assertFalse(owner.durConfig.readerPaletteEnabled)
        assertEquals(before, File(owner.configFilePath).readText())
    }

    @Test
    fun ordinarySaveOwnsDeepCopiesOfAllPaletteModes() {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        val target = owner.durConfig
        val shared = owner.shareConfig
        val expected = target.readerPalette.day.numberColor
        val sharedExpected = shared.readerPalette.day.numberColor

        owner.save()
        target.readerPalette.day.numberColor = 701
        target.readerPalette.night.numberColor = 702
        target.readerPalette.eInk.numberColor = 703
        shared.readerPalette.day.numberColor = 704
        shared.readerPalette.night.numberColor = 705
        shared.readerPalette.eInk.numberColor = 706
        dispatcher.runNext()

        val reloaded = ReadBookConfigShared(preferences)
        assertEquals(expected, reloaded.durConfig.readerPalette.day.numberColor)
        assertEquals(ReaderPaletteSet().night, reloaded.durConfig.readerPalette.night)
        assertEquals(ReaderPaletteSet().eInk, reloaded.durConfig.readerPalette.eInk)
        assertEquals(sharedExpected, reloaded.shareConfig.readerPalette.day.numberColor)
        assertEquals(ReaderPaletteSet().night, reloaded.shareConfig.readerPalette.night)
        assertEquals(ReaderPaletteSet().eInk, reloaded.shareConfig.readerPalette.eInk)
    }

    @Test
    fun oldQueuedSaveCannotOverwriteSuccessfulApply() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        val target = owner.durConfig
        owner.shareConfig.textSize = 37
        owner.save()
        val oldSave = dispatcher.takeNext()
        val palettes = ReaderPaletteSet().apply { day.numberColor = 801 }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = palettes))
        }
        dispatcher.runNext()
        assertTrue(pending.await().isSuccess)

        oldSave.run()

        assertEquals(palettes, ReadBookConfigShared(preferences).durConfig.readerPalette)
        assertTrue(ReadBookConfigShared(preferences).durConfig.readerPaletteEnabled)
        assertEquals(37, ReadBookConfigShared(preferences).shareConfig.textSize)
    }

    @Test
    fun oldQueuedSavePreservesThemeWriteWithoutOverwritingSharedApply() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        owner.shareLayout = true
        val target = owner.config
        owner.durConfig.textSize = 39
        owner.save()
        val oldSave = dispatcher.takeNext()
        val palettes = ReaderPaletteSet().apply { day.numberColor = 803 }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = palettes))
        }
        dispatcher.runNext()
        assertTrue(pending.await().isSuccess)

        oldSave.run()

        val reloaded = ReadBookConfigShared(preferences)
        assertEquals(palettes, reloaded.config.readerPalette)
        assertTrue(reloaded.config.readerPaletteEnabled)
        assertEquals(39, reloaded.durConfig.textSize)
    }

    @Test
    fun newerSaveRequestInvalidatesPendingApply() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        val target = owner.durConfig
        val original = target.readerPalette
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))
        }
        owner.save()
        dispatcher.runNext()

        val result = pending.await()
        assertTrue(result.isFailure)
        assertEquals(
            ReaderPaletteApplyFailure.TARGET_CHANGED,
            (result.exceptionOrNull() as ReaderPaletteApplyException).reason,
        )
        assertSame(original, target.readerPalette)
        assertFalse(target.readerPaletteEnabled)
        dispatcher.runNext()
        assertFalse(ReadBookConfigShared(preferences).durConfig.readerPaletteEnabled)
    }

    @Test
    fun cancellationBeforeIoDoesNotWriteOrPublishPalette() = runBlocking {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        val target = owner.durConfig
        val before = File(owner.configFilePath).readText()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.applyReaderPalette(target, target.copy(readerPalette = ReaderPaletteSet()))
        }
        pending.cancel()
        dispatcher.runNext()
        pending.join()

        assertTrue(pending.isCancelled)
        assertFalse(target.readerPaletteEnabled)
        assertEquals(before, File(owner.configFilePath).readText())
    }

    @Test
    fun newestOrdinarySaveOwnsBothFilesEvenWhenOldTaskRunsLast() {
        val dispatcher = QueuedDispatcher()
        owner = ReadBookConfigShared(preferences, dispatcher)
        owner.durConfig.readerPalette.day.numberColor = 901
        owner.shareConfig.textSize = 31
        owner.save()
        val oldSave = dispatcher.takeNext()
        owner.durConfig.readerPalette.day.numberColor = 902
        owner.shareConfig.textSize = 32
        owner.save()
        dispatcher.runNext()

        oldSave.run()

        val reloaded = ReadBookConfigShared(preferences)
        assertEquals(902, reloaded.durConfig.readerPalette.day.numberColor)
        assertEquals(32, reloaded.shareConfig.textSize)
    }

    @Test
    fun modeTextProjectionEditsOnlyTheSelectedMode() {
        val config = ReadStyleConfig(
            textColorStr = "#112233",
            textColorStrNight = "#445566",
            textColorStrEInk = "#778899",
        )
        val nightBefore = config.textColorForMode(ReaderPaletteMode.NIGHT)
        assertEquals(0xff112233.toInt(), config.textColorForMode(ReaderPaletteMode.DAY))
        assertEquals(0xff445566.toInt(), config.textColorForMode(ReaderPaletteMode.NIGHT))
        assertEquals(0xff778899.toInt(), config.textColorForMode(ReaderPaletteMode.EINK))
        assertEquals(nightBefore, config.textColorForMode(ReaderPaletteMode.NIGHT))
        config.setTextColorForMode(ReaderPaletteMode.DAY, 0xffabcdef.toInt())
        assertEquals(0xffabcdef.toInt(), config.textColorForMode(ReaderPaletteMode.DAY))
    }

    @Test
    fun modeTextProjectionRetainsPerModeParseFallbacks() {
        val config = ReadStyleConfig(
            textColorStr = "invalid", textColorStrNight = "invalid", textColorStrEInk = "invalid",
        )
        assertEquals(0xff0b0b0b.toInt(), config.textColorForMode(ReaderPaletteMode.DAY))
        assertEquals(0xffadadad.toInt(), config.textColorForMode(ReaderPaletteMode.NIGHT))
        assertEquals(0xff000000.toInt(), config.textColorForMode(ReaderPaletteMode.EINK))
    }

    @Test
    fun modeBackgroundProjectionUsesModeColorAndSharedClampedAlpha() {
        val config = ReadStyleConfig(
            bgStr = "#112233", bgStrNight = "#445566", bgStrEInk = "#778899", bgAlpha = 50,
        )
        assertEquals(0x7f112233, config.bgColorForMode(ReaderPaletteMode.DAY))
        assertEquals(0x7f445566, config.bgColorForMode(ReaderPaletteMode.NIGHT))
        assertEquals(0x7f778899, config.bgColorForMode(ReaderPaletteMode.EINK))
        config.bgAlpha = 200
        assertEquals(0xff112233.toInt(), config.bgColorForMode(ReaderPaletteMode.DAY))
        config.bgAlpha = -10
        assertEquals(0x00112233, config.bgColorForMode(ReaderPaletteMode.DAY))
        config.bgStr = "invalid"
        config.bgStrNight = "invalid"
        config.bgStrEInk = "invalid"
        config.bgAlpha = 50
        ReaderPaletteMode.entries.forEach {
            assertEquals(0x7f000000, config.bgColorForMode(it))
        }
    }

    @Test
    fun modeImageBackgroundProjectionUsesExplicitBaseInsteadOfImageMean() {
        val config = ReadStyleConfig(bgType = 1, bgTypeNight = 2, bgTypeEInk = 1, bgAlpha = 10)
        assertEquals(0xffffffff.toInt(), config.bgColorForMode(ReaderPaletteMode.DAY))
        assertEquals(0xff000000.toInt(), config.bgColorForMode(ReaderPaletteMode.NIGHT))
        assertEquals(0xffffffff.toInt(), config.bgColorForMode(ReaderPaletteMode.EINK))
        config.bgMeanColor = 0x12345678
        assertEquals(0xffffffff.toInt(), config.bgColorForMode(ReaderPaletteMode.DAY))
        ReaderPaletteMode.entries.forEach {
            config.setBackgroundSettingsForMode(it, io.legado.app.model.read.ReaderBackgroundSettings(baseColor = 0x12345678))
            assertEquals(0xff345678.toInt(), config.bgColorForMode(it))
        }
        config.bgTypeNight = 0
        config.bgStrNight = "#abcdef"
        assertEquals(0x19abcdef, config.bgColorForMode(ReaderPaletteMode.NIGHT))
        assertEquals(0xff345678.toInt(), config.bgColorForMode(ReaderPaletteMode.DAY))
    }


    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>(8)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        fun takeNext(): Runnable =
            checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "IO task was not dispatched" }

        fun runNext() = takeNext().run()
    }

    private class TestPreferences : PreferenceProvider {
        private val values = mutableMapOf<String, Any>()
        private val changes = PreferenceChangeNotifier()

        override fun addPreferenceChangeListener(listener: (String) -> Unit): () -> Unit {
            changes.add(listener)
            return { changes.remove(listener) }
        }

        override fun getString(key: String, default: String) = values[key] as? String ?: default
        override fun getStringOrNull(key: String) = values[key] as? String
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
            changes.notifyChanged(key)
        }
        override fun putInt(key: String, value: Int) { values[key] = value; changes.notifyChanged(key) }
        override fun putBoolean(key: String, value: Boolean) { values[key] = value; changes.notifyChanged(key) }
        override fun putLong(key: String, value: Long) { values[key] = value; changes.notifyChanged(key) }
        override fun putFloat(key: String, value: Float) { values[key] = value; changes.notifyChanged(key) }
        override fun remove(key: String) { values.remove(key); changes.notifyChanged(key) }
        override fun contains(key: String) = values.containsKey(key)
        override fun getAll(): Map<String, *> = values.toMap()
    }
}
