package io.legado.app.model.read

import io.legado.app.data.entities.BookHighlight

/** 将持久化高亮重锚到当前章节文本，并按最新记录优先合并样式通道。 */
object BookHighlightMatcher {

    const val MAX_HIGHLIGHT_TEXT_LENGTH = 10_000

    fun match(
        text: String,
        highlights: List<BookHighlight>,
        bookUrl: String,
        chapterIndex: Int
    ): List<ColorRuleMatch> {
        if (!withinLimits(text, highlights)) return emptyList()
        val ranges = highlights.asSequence()
            .filter { it.bookUrl == bookUrl && it.chapterIndex == chapterIndex }
            .mapNotNull { highlight -> resolveRange(text, highlight)?.let { highlight to it } }
            .sortedByDescending { it.first.time }
            .toList()
        if (ranges.isEmpty()) return emptyList()
        val boundaries = buildSet {
            ranges.forEach { (_, range) -> add(range.first); add(range.last + 1) }
        }.sorted()
        val result = ArrayList<ColorRuleMatch>()
        for (index in 0 until boundaries.lastIndex) {
            val start = boundaries[index]
            val end = boundaries[index + 1]
            val covering = ranges.filter { (_, range) -> range.first <= start && range.last + 1 >= end }
            val foreground = covering.firstOrNull { it.first.foregroundColor != null }?.first?.foregroundColor
            val background = covering.firstOrNull { it.first.backgroundColor != null }?.first?.backgroundColor
            if (foreground == null && background == null) continue
            val current = ColorRuleMatch(start, end, foreground, background)
            val previous = result.lastOrNull()
            if (previous != null && previous.endExclusive == start &&
                previous.foregroundColor == current.foregroundColor &&
                previous.backgroundColor == current.backgroundColor
            ) result[result.lastIndex] = previous.copy(endExclusive = end)
            else result += current
        }
        return result
    }

    fun findAt(
        text: String,
        highlights: List<BookHighlight>,
        bookUrl: String,
        chapterIndex: Int,
        position: Int
    ): BookHighlight? {
        if (!withinLimits(text, highlights) || position !in text.indices) return null
        return highlights.asSequence()
            .filter { it.bookUrl == bookUrl && it.chapterIndex == chapterIndex }
            .mapNotNull { highlight -> resolveRange(text, highlight)?.let { highlight to it } }
            .filter { (_, range) -> position in range }
            .maxByOrNull { it.first.time }
            ?.first
    }

    private fun withinLimits(text: String, highlights: List<BookHighlight>): Boolean =
        text.isNotEmpty() && text.length <= ColorRuleMatcher.MAX_CHAPTER_LENGTH &&
            highlights.size <= ColorRuleMatcher.MAX_RULES

    private fun resolveRange(text: String, highlight: BookHighlight): IntRange? {
        val start = highlight.chapterPos
        val end = highlight.chapterPosEnd
        if (start < 0 || end <= start ||
            highlight.bookText.length > MAX_HIGHLIGHT_TEXT_LENGTH ||
            highlight.bookText.isBlank()
        ) return null
        if (end <= text.length && text.substring(start, end) == highlight.bookText) {
            return start until end
        }
        // Only the closest occurrence on each side can win; ties retain the earlier occurrence.
        val before = text.lastIndexOf(highlight.bookText, startIndex = minOf(start, text.length))
        val after = text.indexOf(highlight.bookText, startIndex = start)
        val nearest = when {
            before < 0 -> after
            after < 0 -> before
            start - before <= after - start -> before
            else -> after
        }
        return if (nearest >= 0) nearest until nearest + highlight.bookText.length else null
    }
}
