package io.legado.app.model.read

import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.ReadColorRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMatcherLimitsTest {

    @Test
    fun keywordMatchesAreBoundedPerRule() {
        val matches = ColorRuleMatcher.match("x ".repeat(1001), listOf(keyword()), "book")
        assertTrue("Keyword matching must stop at 1000 occurrences", matches.size <= 1000)
    }

    @Test
    fun keywordMatchesAtLimitKeepOriginalOffsets() {
        val matches = ColorRuleMatcher.match("x ".repeat(1000), listOf(keyword()), "book")
        assertEquals((0 until 1000).map { ColorRuleMatch(it * 2, it * 2 + 1, 1, null) }, matches)
    }

    @Test
    fun pairMatchesAreBoundedPerRule() {
        val matches = ColorRuleMatcher.match("(x) ".repeat(1001), listOf(pair()), "book")
        // A pair contributes two separated delimiter spans when content styling is disabled.
        assertTrue("Pair matching must stop at 1000 pairs", matches.size <= 2000)
    }

    @Test
    fun pairMatchesAtLimitKeepBothDelimiters() {
        val matches = ColorRuleMatcher.match("(x) ".repeat(1000), listOf(pair()), "book")
        val expected = (0 until 1000).flatMap {
            listOf(ColorRuleMatch(it * 4, it * 4 + 1, 1, null), ColorRuleMatch(it * 4 + 2, it * 4 + 3, 1, null))
        }
        assertEquals(expected, matches)
    }

    @Test
    fun excessivePairNestingSkipsRuleWithoutMispairedDelimiters() {
        val text = "(".repeat(1001) + "x" + ")".repeat(1001)
        assertTrue(ColorRuleMatcher.match(text, listOf(pair()), "book").isEmpty())
    }

    @Test
    fun oversizedChapterSkipsAutomaticColoringRatherThanMatchingTruncatedPrefix() {
        val text = "x" + " ".repeat(500_000)
        assertTrue(ColorRuleMatcher.match(text, listOf(keyword()), "book").isEmpty())
        assertEquals(500_001, text.length)
    }

    @Test
    fun chapterAtLimitStillMatchesItsLastCharacter() {
        val text = " ".repeat(499_999) + "x"
        assertEquals(
            listOf(ColorRuleMatch(499_999, 500_000, 1, null)),
            ColorRuleMatcher.match(text, listOf(keyword()), "book")
        )
    }

    @Test
    fun oversizedChapterSkipsHighlightColoringAndHitTesting() {
        val text = "x" + " ".repeat(500_000)
        val highlights = listOf(highlight())
        assertTrue(BookHighlightMatcher.match(text, highlights, "book", 0).isEmpty())
        assertNull(BookHighlightMatcher.findAt(text, highlights, "book", 0, 0))
    }

    @Test
    fun highlightAtChapterLimitRetainsAbsolutePosition() {
        val text = " ".repeat(499_999) + "x"
        val mark = highlight().copy(chapterPos = 499_999, chapterPosEnd = 500_000)
        assertEquals(
            listOf(ColorRuleMatch(499_999, 500_000, 1, null)),
            BookHighlightMatcher.match(text, listOf(mark), "book", 0)
        )
        assertEquals(mark, BookHighlightMatcher.findAt(text, listOf(mark), "book", 0, 499_999))
    }

    @Test
    fun oversizedRuleCollectionSkipsColoringInsteadOfSelectingAnArbitraryPrefix() {
        val rules = List(1001) { keyword().copy(id = it.toLong()) }
        assertTrue(ColorRuleMatcher.match("x", rules, "book").isEmpty())
    }

    @Test
    fun ruleCollectionAtLimitPreservesBookScopePriority() {
        val rules = List(999) { keyword().copy(id = it.toLong()) } +
            keyword().copy(id = 1000, bookUrl = "book", foregroundColor = 2)
        assertEquals(listOf(ColorRuleMatch(0, 1, 2, null)), ColorRuleMatcher.match("x", rules, "book"))
    }

    @Test
    fun oversizedHighlightCollectionSkipsColoringAndHitTesting() {
        val highlights = List(1001) { highlight().copy(time = it.toLong()) }
        assertTrue(BookHighlightMatcher.match("x", highlights, "book", 0).isEmpty())
        assertNull(BookHighlightMatcher.findAt("x", highlights, "book", 0, 0))
    }

    @Test
    fun blankHighlightTextIsRejectedEvenWhenCoordinatesAreValid() {
        listOf("", " ", "\t").forEach { quote ->
            val highlights = listOf(highlight().copy(bookText = quote))
            assertTrue("Blank quote: '$quote'", BookHighlightMatcher.match(" x\t", highlights, "book", 0).isEmpty())
            assertNull(BookHighlightMatcher.findAt(" x\t", highlights, "book", 0, 0))
        }
    }

    @Test
    fun malformedHighlightCoordinatesCannotBeRescuedByReanchoring() {
        listOf(
            highlight().copy(chapterPos = -1),
            highlight().copy(chapterPosEnd = 0),
            highlight().copy(chapterPos = Int.MAX_VALUE, chapterPosEnd = Int.MIN_VALUE)
        ).forEach { mark ->
            assertTrue(mark.toString(), BookHighlightMatcher.match("xx", listOf(mark), "book", 0).isEmpty())
            assertNull(BookHighlightMatcher.findAt("xx", listOf(mark), "book", 0, 0))
        }
    }

    @Test
    fun historicalHighlightWithDifferentCoordinateLengthReanchorsByText() {
        val mark = highlight().copy(chapterPosEnd = 2)
        assertEquals(
            listOf(ColorRuleMatch(0, 1, 1, null)),
            BookHighlightMatcher.match("xx", listOf(mark), "book", 0)
        )
        assertEquals(mark, BookHighlightMatcher.findAt("xx", listOf(mark), "book", 0, 0))
        assertNull(BookHighlightMatcher.findAt("xx", listOf(mark), "book", 0, 1))
    }

    @Test
    fun validHighlightStillReanchorsAndLatestRecordWins() {
        val older = highlight().copy(time = 1, chapterPos = 8, chapterPosEnd = 9)
        val newer = older.copy(time = 2, foregroundColor = 2)
        assertEquals(
            listOf(ColorRuleMatch(1, 2, 2, null)),
            BookHighlightMatcher.match(" x", listOf(older, newer), "book", 0)
        )
        assertEquals(newer, BookHighlightMatcher.findAt(" x", listOf(older, newer), "book", 0, 1))
    }

    @Test
    fun pairRulesWithSharedDelimitersRetainIdPriorityDespiteDifferentLeftLengths() {
        val first = pair().copy(
            id = 1, pairLeft = "a", contentEnabled = true, contentForegroundColor = 1
        )
        val second = first.copy(id = 2, pairLeft = "ab", foregroundColor = 2, contentForegroundColor = 2)
        assertEquals(
            listOf(ColorRuleMatch(0, 4, 1, null)),
            ColorRuleMatcher.match("abx)", listOf(second, first), "book")
        )
    }

    @Test
    fun identicalPairContentDoesNotGainSpecificityFromDuplicateRules() {
        val first = pair().copy(
            id = 1, pairLeft = "ab", contentEnabled = true, contentForegroundColor = 1
        )
        val second = first.copy(id = 2, pairLeft = "b", foregroundColor = 2, contentForegroundColor = 2)
        assertEquals(
            listOf(ColorRuleMatch(0, 4, 1, null)),
            ColorRuleMatcher.match("abx)", listOf(second, second.copy(id = 3), first), "book")
        )
    }

    @Test
    fun reanchoringRetainsEarlierOccurrenceOnEqualDistance() {
        val mark = highlight().copy(chapterPos = 2, chapterPosEnd = 3)
        assertEquals(
            listOf(ColorRuleMatch(0, 1, 1, null)),
            BookHighlightMatcher.match("x   x", listOf(mark), "book", 0)
        )
    }

    @Test
    fun reanchoringDoesNotTruncateOccurrencesBeforeTheNearestOne() {
        val mark = highlight().copy(chapterPos = 2001, chapterPosEnd = 2002)
        assertEquals(
            listOf(ColorRuleMatch(2000, 2001, 1, null)),
            BookHighlightMatcher.match("x ".repeat(1002), listOf(mark), "book", 0)
        )
    }

    private fun keyword() = ReadColorRule(keyword = "x", foregroundColor = 1)

    private fun pair() = ReadColorRule(
        ruleType = ReadColorRule.TYPE_PAIR,
        pairLeft = "(",
        pairRight = ")",
        foregroundColor = 1,
        contentEnabled = false
    )

    private fun highlight() = BookHighlight(
        time = 1,
        bookUrl = "book",
        chapterPos = 0,
        chapterPosEnd = 1,
        bookText = "x",
        foregroundColor = 1
    )
}
