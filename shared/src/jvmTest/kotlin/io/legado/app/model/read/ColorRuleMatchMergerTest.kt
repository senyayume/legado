package io.legado.app.model.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorRuleMatchMergerTest {
    @Test
    fun orderedProjectionVisitsEachIntervalOnceAcrossGapsAndWideColumns() {
        val count = 1000
        val matches = CountingList(List(count) { ColorRuleMatch(it * 4, it * 4 + 1, it, null) })
        val cursor = OrderedColorRuleMatchCursor(matches)
        for (index in 0 until count) {
            assertEquals(index, cursor.firstIntersecting(index * 4, index * 4 + 1)?.foregroundColor)
            assertEquals(null, cursor.firstIntersecting(index * 4 + 1, index * 4 + 3))
        }
        assertTrue("Input visits: ${matches.visits}", matches.visits <= count * 4)
        val wide = OrderedColorRuleMatchCursor(listOf(
            ColorRuleMatch(1, 2, 1, null), ColorRuleMatch(2, 8, 2, null)))
        assertEquals(1, wide.firstIntersecting(0, 4)?.foregroundColor)
        assertEquals(2, wide.firstIntersecting(4, 6)?.foregroundColor)
        assertEquals(2, wide.firstIntersecting(6, 8)?.foregroundColor)
        assertEquals(null, wide.firstIntersecting(8, 9))
    }

    @Test
    fun alternatingIntervalsMergeWithLinearInputVisits() {
        val count = 1000
        val base = CountingList(List(count) { ColorRuleMatch(it * 4, it * 4 + 2, 7, null) })
        val overlay = CountingList(List(count) { ColorRuleMatch(it * 4 + 1, it * 4 + 3, null, 9) })
        val result = ColorRuleMatchMerger.merge(base, overlay)
        assertEquals(count * 3, result.size)
        assertEquals(ColorRuleMatch(0, 1, 7, null), result[0])
        assertEquals(ColorRuleMatch(1, 2, 7, 9), result[1])
        assertEquals(ColorRuleMatch(2, 3, null, 9), result[2])
        assertTrue("Input visits: ${base.visits + overlay.visits}", base.visits + overlay.visits <= count * 20)
    }

    private class CountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var visits = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T = values[index].also { visits++ }
    }
}
