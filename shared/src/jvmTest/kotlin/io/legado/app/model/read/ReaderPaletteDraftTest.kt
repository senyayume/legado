package io.legado.app.model.read

import io.legado.app.help.config.ReadStyleConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaletteDraftTest {

    @Test
    fun draftOwnsDeepCopiesOfEveryModeAndExposesDetachedSnapshots() {
        val original = ReaderPaletteSet()
        val expected = original.deepCopy()
        val draft = ReaderPaletteDraft(ReadStyleConfig(readerPalette = original))

        ReaderPaletteMode.entries.forEach { mode ->
            original.forMode(mode).numberColor = 17
        }
        assertEquals(expected, draft.state.value.snapshot().readerPalette)

        val snapshot = draft.state.value.snapshot().readerPalette
        ReaderPaletteMode.entries.forEach { mode ->
            snapshot.forMode(mode).numberColor = 29
            draft.state.value.palette(mode).letterColor = 31
        }
        assertEquals(expected, draft.state.value.snapshot().readerPalette)

        val replacement = ReaderPalette(numberColor = 43)
        draft.update(ReaderPaletteMode.DAY, replacement)
        replacement.numberColor = 47
        assertEquals(43, draft.state.value.palette(ReaderPaletteMode.DAY).numberColor)
    }

    @Test
    fun cancelLeavesOriginalUntouchedAndNeverWrites() = runBlocking {
        val original = ReaderPaletteSet()
        val expected = original.deepCopy()
        val draft = ReaderPaletteDraft(ReadStyleConfig(readerPalette = original))
        draft.update(ReaderPaletteMode.NIGHT, ReaderPalette(numberColor = 53))

        draft.cancel()
        var writes = 0
        val result = draft.apply {
            writes++
            Result.success(Unit)
        }

        assertEquals(expected, original)
        assertEquals(ReaderPaletteDraftStatus.CANCELLED, draft.state.value.status)
        assertTrue(result.isFailure)
        assertEquals(0, writes)
    }

    @Test
    fun editingEachModePreservesAllOtherModes() {
        val original = ReaderPaletteSet()
        val draft = ReaderPaletteDraft(ReadStyleConfig(readerPalette = original))
        val expected = original.deepCopy()

        ReaderPaletteMode.entries.forEachIndexed { index, mode ->
            val changed = expected.forMode(mode).copy(numberColor = 61 + index)
            draft.update(mode, changed)
            expected.setForMode(mode, changed.copy())

            assertEquals(expected, draft.state.value.snapshot().readerPalette)
        }
        assertEquals(ReaderPaletteSet(), original)
    }

    @Test
    fun readerResetPreservesHighlightFieldsAndOtherModes() {
        ReaderPaletteMode.entries.forEach { mode ->
            val original = ReaderPaletteSet()
            val customized = customizedPalette()
            original.setForMode(mode, customized)
            val expected = original.deepCopy().apply {
                setForMode(
                    mode,
                    customized.resetReaderFields(ReaderPaletteSet().forMode(mode))
                )
            }
            val draft = ReaderPaletteDraft(ReadStyleConfig(readerPalette = original))

            draft.reset(mode, ReaderPaletteTab.READER)

            assertEquals(expected, draft.state.value.snapshot().readerPalette)
            assertEquals(customized, original.forMode(mode))
        }
    }

    @Test
    fun highlightResetPreservesReaderFieldsAndOtherModes() {
        ReaderPaletteMode.entries.forEach { mode ->
            val original = ReaderPaletteSet()
            val customized = customizedPalette()
            original.setForMode(mode, customized)
            val expected = original.deepCopy().apply {
                setForMode(
                    mode,
                    customized.resetHighlightFields(ReaderPaletteSet().forMode(mode))
                )
            }
            val draft = ReaderPaletteDraft(ReadStyleConfig(readerPalette = original))

            draft.reset(mode, ReaderPaletteTab.HIGHLIGHT)

            assertEquals(expected, draft.state.value.snapshot().readerPalette)
            assertEquals(customized, original.forMode(mode))
        }
    }

    @Test
    fun successfulApplyWritesAllModesOnceAndDetachesCallbackPayload() = runBlocking {
        val original = ReaderPaletteSet()
        val draft = ReaderPaletteDraft(ReadStyleConfig(readerPalette = original))
        draft.update(ReaderPaletteMode.EINK, customizedPalette())
        val expected = draft.state.value.snapshot().readerPalette
        var writes = 0
        var saved: ReaderPaletteSet? = null
        val writer: suspend (ReadStyleConfig) -> Result<Unit> = { config ->
            val palettes = config.readerPalette
            writes++
            saved = palettes.deepCopy()
            palettes.day.numberColor = 73
            Result.success(Unit)
        }

        assertTrue(draft.apply(writer).isSuccess)
        draft.apply(writer)

        assertEquals(1, writes)
        assertEquals(expected, saved)
        assertEquals(expected, draft.state.value.snapshot().readerPalette)
        assertEquals(ReaderPaletteSet(), original)
        assertEquals(ReaderPaletteDraftStatus.APPLIED, draft.state.value.status)
    }

    @Test
    fun failedApplyKeepsEditsAndAllowsExplicitRetry() = runBlocking {
        val original = ReaderPaletteSet()
        val draft = ReaderPaletteDraft(ReadStyleConfig(readerPalette = original))
        draft.update(ReaderPaletteMode.NIGHT, customizedPalette())
        val expected = draft.state.value.snapshot().readerPalette
        val failure = IllegalStateException("storage unavailable")
        var writes = 0

        val result = draft.apply { config ->
            val palettes = config.readerPalette
            writes++
            palettes.night.numberColor = 79
            Result.failure(failure)
        }

        assertSame(failure, result.exceptionOrNull())
        assertEquals(ReaderPaletteDraftStatus.EDITING, draft.state.value.status)
        assertEquals(expected, draft.state.value.snapshot().readerPalette)
        assertEquals(ReaderPaletteSet(), original)
        assertEquals(1, writes)

        assertTrue(draft.apply {
            writes++
            assertEquals(expected, it.readerPalette)
            Result.success(Unit)
        }.isSuccess)
        assertEquals(2, writes)
        assertEquals(ReaderPaletteDraftStatus.APPLIED, draft.state.value.status)
    }

    @Test
    fun applyingRejectsDuplicateWriteAndDraftChangesUntilWriterCompletes() = runBlocking {
        val draft = ReaderPaletteDraft(ReadStyleConfig())
        val expected = draft.state.value.snapshot().readerPalette
        val releaseWriter = CompletableDeferred<Unit>()
        var writes = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            draft.apply {
                writes++
                releaseWriter.await()
                Result.success(Unit)
            }
        }

        try {
            assertEquals(ReaderPaletteDraftStatus.APPLYING, draft.state.value.status)
            val duplicate = draft.apply {
                writes++
                Result.success(Unit)
            }
            assertTrue(duplicate.isFailure)
            assertEquals(1, writes)
            assertFalse(first.isCompleted)

            draft.update(ReaderPaletteMode.DAY, customizedPalette())
            draft.reset(ReaderPaletteMode.NIGHT, ReaderPaletteTab.READER)
            draft.cancel()
            assertEquals(expected, draft.state.value.snapshot().readerPalette)
            assertEquals(ReaderPaletteDraftStatus.APPLYING, draft.state.value.status)
        } finally {
            releaseWriter.complete(Unit)
        }
        assertTrue(first.await().isSuccess)
        assertEquals(ReaderPaletteDraftStatus.APPLIED, draft.state.value.status)
    }

    @Test
    fun thrownWriterFailureIsReportedWithoutLosingDraft() = runBlocking {
        val draft = ReaderPaletteDraft(ReadStyleConfig())
        draft.update(ReaderPaletteMode.DAY, customizedPalette())
        val expected = draft.state.value.snapshot().readerPalette
        val failure = IllegalStateException("write failed")

        val result = draft.apply { throw failure }

        assertSame(failure, result.exceptionOrNull())
        assertSame(failure, draft.state.value.failure)
        assertEquals(ReaderPaletteDraftStatus.EDITING, draft.state.value.status)
        assertEquals(expected, draft.state.value.snapshot().readerPalette)
    }

    @Test
    fun cancelledWriterRestoresEditingAndPropagatesCancellation() = runBlocking {
        val draft = ReaderPaletteDraft(ReadStyleConfig())
        draft.update(ReaderPaletteMode.DAY, customizedPalette())
        val expected = draft.state.value.snapshot().readerPalette
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            draft.apply {
                entered.complete(Unit)
                release.await()
                Result.success(Unit)
            }
        }
        entered.await()

        operation.cancel()
        operation.join()

        assertTrue(operation.isCancelled)
        assertEquals(ReaderPaletteDraftStatus.EDITING, draft.state.value.status)
        assertEquals(expected, draft.state.value.snapshot().readerPalette)
    }

    @Test
    fun cancellationReturnedAsResultStillPropagatesAndRestoresEditing() = runBlocking {
        val draft = ReaderPaletteDraft(ReadStyleConfig())
        val cancellation = CancellationException("cancelled")
        var propagated: CancellationException? = null

        try {
            draft.apply { Result.failure(cancellation) }
        } catch (error: CancellationException) {
            propagated = error
        }

        assertSame(cancellation, propagated)
        assertEquals(ReaderPaletteDraftStatus.EDITING, draft.state.value.status)
    }

    private fun ReaderPaletteSet.deepCopy() = ReaderPaletteSet(
        day = day.copy(),
        night = night.copy(),
        eInk = eInk.copy()
    )

    private fun customizedPalette() = ReaderPalette(
        chapterTitleColor = 101,
        quoteSymbolColor = 102,
        quoteContentColor = 103,
        bracketSymbolColor = 104,
        bracketContentColor = 105,
        bracketPairsEnabled = false,
        punctuationColor = 106,
        specialMarkColor = 107,
        numberColor = 108,
        letterColor = 109,
        searchResultColor = 110,
        searchResultBackgroundColor = 111,
        bookmarkColor = 112,
        bookmarkBackgroundColor = 113,
        annotationColor = 114,
        annotationBackgroundColor = 115
    )
}
