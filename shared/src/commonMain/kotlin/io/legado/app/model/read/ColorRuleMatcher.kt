package io.legado.app.model.read

import io.legado.app.data.entities.ReadColorRule

data class ColorRuleMatch(
    val start: Int,
    val endExclusive: Int,
    val foregroundColor: Int?,
    val backgroundColor: Int?,
    val underline: Boolean? = null,
    val bold: Boolean? = null
)

/** 将自动规则投影为章节文本半开区间；非法单条规则只产生空结果。 */
object ColorRuleMatcher {

    const val MAX_REGEX_LENGTH = 200
    const val MAX_REGEX_MATCHES = 1000
    const val MAX_CHAPTER_LENGTH = 500_000
    const val MAX_RULES = 1000

    fun match(
        text: String,
        rules: List<ReadColorRule>,
        bookUrl: String,
        chapterIndex: Int = 0
    ): List<ColorRuleMatch> {
        // Skip coloring, never truncate the chapter or select a prefix of the rule collection.
        if (text.isEmpty() || text.length > MAX_CHAPTER_LENGTH || rules.size > MAX_RULES) {
            return emptyList()
        }
        val matchingRules = rules.asSequence()
            .filter { isRuleActive(it, bookUrl, chapterIndex) }
            .sortedWith(ruleComparator(bookUrl))
            .toList()
        val pairMatchesByRule = matchingRules
            .filter { it.ruleType == ReadColorRule.TYPE_PAIR }
            .associateWith { pairMatches(text, it) }
        val pairSpecificities = pairSpecificities(pairMatchesByRule.values.flatten())

        val candidates = buildList {
            var ordinal = 0
            fun addCandidate(
                rule: ReadColorRule,
                start: Int,
                end: Int,
                specificity: Int = 0,
                foregroundColor: Int? = rule.foregroundColor,
                backgroundColor: Int? = rule.backgroundColor,
                underline: Boolean? = rule.underline,
                bold: Boolean? = rule.bold
            ) {
                if (start < end) add(
                    Candidate(
                        start = start,
                        endExclusive = end,
                        rule = rule,
                        foregroundColor = foregroundColor,
                        backgroundColor = backgroundColor,
                        underline = underline,
                        bold = bold,
                        scopeRank = scopeRank(rule, bookUrl),
                        specificity = specificity,
                        ordinal = ordinal++
                    )
                )
            }
            matchingRules.forEach { rule ->
                when (rule.ruleType) {
                    ReadColorRule.TYPE_PAIR -> pairMatchesByRule[rule].orEmpty().forEach { pair ->
                        val specificity = pairSpecificities.getValue(pair)
                        addCandidate(rule, pair.leftStart, pair.leftEnd, specificity)
                        addCandidate(rule, pair.rightStart, pair.rightEnd, specificity)
                        if (rule.contentEnabled && pair.contentStart < pair.contentEnd) {
                            addCandidate(
                                rule,
                                pair.contentStart,
                                pair.contentEnd,
                                specificity,
                                rule.contentForegroundColor,
                                rule.contentBackgroundColor,
                                rule.contentUnderline,
                                rule.contentBold
                            )
                        }
                    }

                    ReadColorRule.TYPE_REGEX -> regexMatches(text, rule).forEach { (start, end) ->
                        addCandidate(rule, start, end)
                    }

                    else -> {
                        if (rule.keyword.isBlank()) return@forEach
                        var start = text.indexOf(rule.keyword, ignoreCase = !rule.caseSensitive)
                        var count = 0
                        while (start >= 0 && count < MAX_REGEX_MATCHES) {
                            val end = start + rule.keyword.length
                            if (end > start) addCandidate(rule, start, end)
                            count++
                            if (count == MAX_REGEX_MATCHES) break
                            start = text.indexOf(
                                rule.keyword,
                                startIndex = end,
                                ignoreCase = !rule.caseSensitive
                            )
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val boundaries = buildSet {
            add(0)
            add(text.length)
            candidates.forEach {
                add(it.start)
                add(it.endExclusive)
            }
        }.sorted()
        val starts = candidates.groupBy { it.start }
        val ends = candidates.groupBy { it.endExclusive }
        val foreground = mutableListOf<Candidate>()
        val background = mutableListOf<Candidate>()
        val underline = mutableListOf<Candidate>()
        val bold = mutableListOf<Candidate>()
        val result = ArrayList<ColorRuleMatch>()

        for (index in 0 until boundaries.lastIndex) {
            val start = boundaries[index]
            val end = boundaries[index + 1]
            if (start >= end) continue
            ends[start].orEmpty().forEach { candidate ->
                foreground.remove(candidate)
                background.remove(candidate)
                underline.remove(candidate)
                bold.remove(candidate)
            }
            starts[start].orEmpty().forEach { candidate ->
                if (candidate.foregroundColor != null) foreground += candidate
                if (candidate.backgroundColor != null) background += candidate
                if (candidate.underline != null) underline += candidate
                if (candidate.bold != null) bold += candidate
            }
            val match = ColorRuleMatch(
                start = start,
                endExclusive = end,
                foregroundColor = foreground.minWithOrNull(candidateComparator)?.foregroundColor,
                backgroundColor = background.minWithOrNull(candidateComparator)?.backgroundColor,
                underline = underline.minWithOrNull(candidateComparator)?.underline,
                bold = bold.minWithOrNull(candidateComparator)?.bold
            )
            if (match.foregroundColor == null && match.backgroundColor == null &&
                match.underline == null && match.bold == null
            ) continue
            val previous = result.lastOrNull()
            if (previous != null && previous.endExclusive == start && previous.sameStyle(match)) {
                result[result.lastIndex] = previous.copy(endExclusive = end)
            } else {
                result += match
            }
        }
        return result
    }

    private fun ColorRuleMatch.sameStyle(other: ColorRuleMatch): Boolean =
        foregroundColor == other.foregroundColor &&
            backgroundColor == other.backgroundColor &&
            underline == other.underline &&
            bold == other.bold

    private data class Candidate(
        val start: Int,
        val endExclusive: Int,
        val rule: ReadColorRule,
        val foregroundColor: Int?,
        val backgroundColor: Int?,
        val underline: Boolean?,
        val bold: Boolean?,
        val scopeRank: Int,
        val specificity: Int,
        val ordinal: Int
    )

    private val candidateComparator = compareBy<Candidate> { it.scopeRank }
        .thenBy { it.rule.sortOrder }
        .thenByDescending { it.specificity }
        .thenBy { it.rule.id }
        .thenBy { it.ordinal }

    private fun ruleComparator(bookUrl: String) = compareBy<ReadColorRule> {
        scopeRank(it, bookUrl)
    }.thenBy { it.sortOrder }.thenBy { it.id }

    private fun scopeRank(rule: ReadColorRule, bookUrl: String): Int =
        if (rule.bookUrl.isNotBlank() && rule.bookUrl == bookUrl) 0 else 1

    private fun isRuleActive(rule: ReadColorRule, bookUrl: String, chapterIndex: Int): Boolean {
        if (!rule.enabled || (rule.bookUrl.isNotBlank() && rule.bookUrl != bookUrl)) return false
        val chapterNumber = chapterIndex + 1
        if (rule.chapterStart != null && chapterNumber < rule.chapterStart) return false
        if (rule.chapterEnd != null && chapterNumber > rule.chapterEnd) return false
        return parseRanges(rule.excludedChapterRanges).none { chapterNumber in it }
    }

    private fun regexMatches(text: String, rule: ReadColorRule): List<Pair<Int, Int>> {
        if (rule.pattern.isBlank() || rule.pattern.length > MAX_REGEX_LENGTH) return emptyList()
        val regex = runCatching {
            Regex(
                rule.pattern,
                if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            )
        }.getOrNull() ?: return emptyList()
        return regex.findAll(text)
            .take(MAX_REGEX_MATCHES)
            .mapNotNull { match ->
                val start = match.range.first
                val end = match.range.last + 1
                if (end > start) start to end else null
            }
            .toList()
    }

    private fun parseRanges(value: String): List<IntRange> {
        val values = Regex("-?\\d+").findAll(value.take(1000)).take(64)
            .mapNotNull { it.value.toIntOrNull() }.toList()
        return values.chunked(2).mapNotNull { pair ->
            val start = pair.getOrNull(0) ?: return@mapNotNull null
            val end = pair.getOrNull(1) ?: start
            minOf(start, end)..maxOf(start, end)
        }
    }

    private data class PairMatch(
        val leftStart: Int,
        val leftEnd: Int,
        val rightStart: Int,
        val rightEnd: Int,
        val contentStart: Int,
        val contentEnd: Int
    )

    private fun pairMatches(text: String, rule: ReadColorRule): List<PairMatch> {
        if (rule.pairLeft.isEmpty() || rule.pairRight.isEmpty() || rule.pairLeft == rule.pairRight) {
            return emptyList()
        }
        val stack = ArrayDeque<Int>()
        val result = ArrayList<PairMatch>()
        var position = 0
        while (position < text.length && result.size < MAX_REGEX_MATCHES) {
            when {
                text.startsWith(rule.pairLeft, position, ignoreCase = !rule.caseSensitive) -> {
                    // Dropping an opener would mispair later closers, so skip this rule instead.
                    if (stack.size == MAX_REGEX_MATCHES) return emptyList()
                    stack.addLast(position)
                    position += rule.pairLeft.length
                }
                text.startsWith(rule.pairRight, position, ignoreCase = !rule.caseSensitive) &&
                    stack.isNotEmpty() -> {
                    val start = stack.removeLast()
                    result += PairMatch(
                        leftStart = start,
                        leftEnd = start + rule.pairLeft.length,
                        rightStart = position,
                        rightEnd = position + rule.pairRight.length,
                        contentStart = start + rule.pairLeft.length,
                        contentEnd = position
                    )
                    position += rule.pairRight.length
                }
                else -> position++
            }
        }
        return result.sortedBy { it.leftStart }
    }

    private fun pairSpecificities(pairs: List<PairMatch>): Map<PairMatch, Int> {
        if (pairs.isEmpty()) return emptyMap()
        val sorted = pairs.sortedWith(compareBy<PairMatch> { it.contentStart }.thenByDescending { it.contentEnd })
        val endCounts = IntArray(pairs.maxOf { it.contentEnd } + 2)
        val sameDelimiters = mutableMapOf<Pair<Int, Int>, Int>()
        val result = HashMap<PairMatch, Int>()
        var inserted = 0
        var index = 0
        // Sweep content starts; a Fenwick tree counts enclosing ends without scanning all pairs.
        // Equal content ranges are queried together before insertion to preserve strict containment.
        while (index < sorted.size) {
            val first = sorted[index]
            var groupEnd = index + 1
            while (groupEnd < sorted.size &&
                sorted[groupEnd].contentStart == first.contentStart &&
                sorted[groupEnd].contentEnd == first.contentEnd
            ) groupEnd++
            var smallerEnds = 0
            var cursor = first.contentEnd
            while (cursor > 0) {
                smallerEnds += endCounts[cursor]
                cursor -= cursor and -cursor
            }
            for (pairIndex in index until groupEnd) {
                val pair = sorted[pairIndex]
                // Shared delimiter starts are excluded even when delimiter lengths differ.
                result[pair] = inserted - smallerEnds -
                    (sameDelimiters[pair.leftStart to pair.rightStart] ?: 0)
            }
            for (pairIndex in index until groupEnd) {
                val pair = sorted[pairIndex]
                cursor = pair.contentEnd + 1
                while (cursor < endCounts.size) {
                    endCounts[cursor]++
                    cursor += cursor and -cursor
                }
                val delimiters = pair.leftStart to pair.rightStart
                sameDelimiters[delimiters] = (sameDelimiters[delimiters] ?: 0) + 1
                inserted++
            }
            index = groupEnd
        }
        return result
    }
}
