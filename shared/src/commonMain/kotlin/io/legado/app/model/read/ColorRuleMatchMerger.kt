package io.legado.app.model.read

/** 按样式通道叠加区间，overlay 未定义的通道继承 base。 */
object ColorRuleMatchMerger {

    fun merge(base: List<ColorRuleMatch>, overlay: List<ColorRuleMatch>): List<ColorRuleMatch> {
        if (base.isEmpty()) return overlay
        if (overlay.isEmpty()) return base
        val boundaries = buildSet {
            base.forEach { add(it.start); add(it.endExclusive) }
            overlay.forEach { add(it.start); add(it.endExclusive) }
        }.sorted()
        val result = ArrayList<ColorRuleMatch>()
        val baseCursor = OrderedColorRuleMatchCursor(base)
        val overlayCursor = OrderedColorRuleMatchCursor(overlay)
        for (index in 0 until boundaries.lastIndex) {
            val start = boundaries[index]
            val end = boundaries[index + 1]
            val low = baseCursor.firstIntersecting(start, end)
            val high = overlayCursor.firstIntersecting(start, end)
            val match = ColorRuleMatch(
                start,
                end,
                high?.foregroundColor ?: low?.foregroundColor,
                high?.backgroundColor ?: low?.backgroundColor,
                high?.underline ?: low?.underline,
                high?.bold ?: low?.bold
            )
            if (match.foregroundColor == null && match.backgroundColor == null &&
                match.underline == null && match.bold == null
            ) continue
            val previous = result.lastOrNull()
            if (previous != null && previous.endExclusive == start &&
                previous.foregroundColor == match.foregroundColor &&
                previous.backgroundColor == match.backgroundColor &&
                previous.underline == match.underline && previous.bold == match.bold
            ) {
                result[result.lastIndex] = previous.copy(endExclusive = end)
            } else result += match
        }
        return result
    }
}

/** 消费匹配器输出的有序、不重叠区间；调用方的 start 必须单调递增。 */
internal class OrderedColorRuleMatchCursor(private val matches: List<ColorRuleMatch>) {
    private var index = 0

    fun firstIntersecting(start: Int, endExclusive: Int): ColorRuleMatch? {
        while (index < matches.size) {
            val match = matches[index]
            if (match.endExclusive <= start) {
                index++
            } else {
                // 一列可能含多个 UTF-16 单元或字素；只按 start 推进，保留最早相交语义。
                return match.takeIf { it.start < endExclusive }
            }
        }
        return null
    }
}
