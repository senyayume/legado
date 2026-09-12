package io.legado.app.model.read

import io.legado.app.data.entities.ReadColorRule
import org.junit.Assert.*
import org.junit.Test

class ColorRuleTransferTest {
    @Test
    fun exportRemovesIdentityAndBindsBookOnImport() {
        val original = ReadColorRule(
            id = 42, bookUrl = "private-book", keyword = "word",
            foregroundColor = -65536, underline = false, bold = true,
            chapterStart = 2, chapterEnd = 8, excludedChapterRanges = "[[3,4]]"
        )
        val json = ColorRuleTransfer.encode(listOf(original)).getOrThrow()
        assertFalse(json.contains("\"id\""))
        assertFalse(json.contains("private-book"))
        val report = ColorRuleTransfer.decode(json, "new-book").getOrThrow()
        assertEquals(listOf(original.copy(id = 0, bookUrl = "new-book")), report.rules)
    }

    @Test
    fun invalidEntriesDoNotDiscardValidRules() {
        val report = ColorRuleTransfer.decode(
            """{"schemaVersion":1,"rules":[
                {"keyword":"valid"},{"ruleType":"regex","pattern":"["},
                {"ruleType":"pair","pairLeft":"<","pairRight":"<"},
                {"scope":"currentBook","keyword":"book"},
                {"ruleType":"heuristic","keyword":"unsupported"}
            ]}""", ""
        ).getOrThrow()
        assertEquals("valid", report.rules.single().keyword)
        assertEquals(4, report.skippedCount)
        assertEquals(4, report.errors.size)
    }

    @Test
    fun rejectsOversizedInputAndUnknownVersion() {
        assertEquals(ColorRuleError.INPUT_TOO_LARGE,
            (ColorRuleTransfer.decode(" ".repeat(ColorRuleTransfer.MAX_IMPORT_BYTES + 1), "")
                .exceptionOrNull() as ColorRuleException).reason)
        assertEquals(ColorRuleError.UNSUPPORTED_VERSION,
            (ColorRuleTransfer.decode("""{"schemaVersion":2,"rules":[]}""", "")
                .exceptionOrNull() as ColorRuleException).reason)
    }

    @Test
    fun exclusionRangesAreStructuredAndBounded() {
        assertEquals("[[2,3],[5,5]]",
            ColorRuleTransfer.normalizeExcludedChapterRanges(" [[2,3],[5]] "))
        assertNull(ColorRuleTransfer.normalizeExcludedChapterRanges("[2,3]"))
        assertNull(ColorRuleTransfer.normalizeExcludedChapterRanges("[[0,1]]"))
        assertNull(ColorRuleTransfer.normalizeExcludedChapterRanges("[[1.5,3]]"))
        assertNull(ColorRuleTransfer.normalizeExcludedChapterRanges("[[\"2\",3]]"))
    }

    @Test
    fun allPairStyleChannelsRoundTripWithoutNewMatchingModes() {
        val rule = ReadColorRule(
            ruleType = ReadColorRule.TYPE_PAIR, pairLeft = "<", pairRight = ">",
            foregroundColor = 1, backgroundColor = 2,
            contentForegroundColor = 3, contentBackgroundColor = 4,
            underline = false, bold = true, contentUnderline = true, contentBold = false,
            enabled = false, contentEnabled = false, caseSensitive = true,
        )
        val encoded = ColorRuleTransfer.encode(listOf(rule)).getOrThrow()
        assertEquals(rule, ColorRuleTransfer.decode(encoded, "").getOrThrow().rules.single())
    }

    @Test
    fun aThousandRuleLimitHasAnExplicitSkippedReport() {
        val entries = List(ColorRuleTransfer.MAX_RULES + 2) { """{"keyword":"word"}""" }.joinToString(",")
        val result = ColorRuleTransfer.decode("""{"schemaVersion":1,"rules":[$entries]}""", "").getOrThrow()
        assertEquals(ColorRuleTransfer.MAX_RULES, result.rules.size)
        assertEquals(2, result.skippedCount)
        assertEquals(ColorRuleError.INPUT_TOO_LARGE, result.errors.single().reason)
    }

    @Test
    fun invalidChapterBoundsAndMalformedStructureAreRejected() {
        assertTrue(ColorRuleTransfer.validate(ReadColorRule(keyword = "word", chapterStart = 4, chapterEnd = 2)).isFailure)
        assertTrue(ColorRuleTransfer.validate(ReadColorRule(keyword = "word", chapterStart = 0)).isFailure)
        assertTrue(ColorRuleTransfer.decode("""{"schemaVersion":1,"rules":{}}""", "").isFailure)
        assertTrue(ColorRuleTransfer.decode("[", "").isFailure)
        val noId = ColorRuleTransfer.decode(
            """{"schemaVersion":1,"rules":[{"id":99,"bookUrl":"foreign","keyword":"word"}]}""", "book"
        ).getOrThrow()
        assertEquals(0L, noId.rules.single().id)
        assertEquals("", noId.rules.single().bookUrl)
    }
}
