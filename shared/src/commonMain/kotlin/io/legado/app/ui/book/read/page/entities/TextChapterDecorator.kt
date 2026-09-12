package io.legado.app.ui.book.read.page.entities

import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.ReadColorRule
import io.legado.app.model.read.BookHighlightMatcher
import io.legado.app.model.read.ColorRuleMatchMerger
import io.legado.app.model.read.ColorRuleMatcher
import io.legado.app.model.read.OrderedColorRuleMatchCursor
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn

/** 在排版完成后一次性把自动规则和手动高亮投影到列，不触碰分页几何。 */
object TextChapterDecorator {

    fun apply(
        pages: List<TextPage>,
        bookUrl: String,
        chapterIndex: Int,
        rules: List<ReadColorRule>,
        highlights: List<BookHighlight>,
        titleColor: Int? = null,
    ) {
        val logicalText = canonicalText(pages)
        val automatic = ColorRuleMatcher.match(logicalText, rules, bookUrl, chapterIndex)
        val manual = BookHighlightMatcher.match(logicalText, highlights, bookUrl, chapterIndex)
        val matches = ColorRuleMatchMerger.merge(automatic, manual)
        val matchCursor = OrderedColorRuleMatchCursor(matches)
        // 仅处理 owner 接受且有样式的输入；超限时不得再逐条匹配绕过整体预算。
        val manualIdentities = if (manual.isEmpty()) emptyList() else highlights.flatMap { highlight ->
            BookHighlightMatcher.match(logicalText, listOf(highlight), bookUrl, chapterIndex)
                .map { it to highlight.time }
        }
        pages.forEach { page ->
            page.lines.forEach { line ->
                val columns = line.columns
                val positions = columnOffsets(line)
                columns.forEachIndexed { index, column ->
                    if (column !is TextColumn) return@forEachIndexed
                    val content = index >= line.indentSize
                    val start = positions[index]
                    val end = positions[index + 1]
                    val match = if (content) {
                        matchCursor.firstIntersecting(start, end)
                    } else null
                    column.foregroundColor = if (content) {
                        match?.foregroundColor ?: titleColor.takeIf { line.isTitle }
                    } else null
                    column.backgroundColor = match?.backgroundColor
                    column.underline = match?.underline
                    column.bold = match?.bold
                    column.manualHighlightId = if (content) {
                        manualIdentities.asSequence()
                            .filter { (range, _) -> range.start < end && range.endExclusive > start }
                            .maxOfOrNull { (_, time) -> time }
                    } else null
                }
            }
        }
    }

    /**
     * 还原完整章节的 canonical 排版文本，供装饰匹配、选区原文和持久化共用。
     *
     * 按章节顺序传入全部页面；保留缩进、图片/段评占位符以及段末换行，
     * 使 UTF-16 半开区间与排版器写入的 [TextLine.chapterPosition] 一致。
     */
    fun canonicalText(pages: List<TextPage>): String = buildString {
        pages.forEach { page ->
            page.lines.forEach { line ->
                append(line.text)
                if (line.isParagraphEnd) append('\n')
            }
        }
    }

    /**
     * 返回列边界的章节绝对 UTF-16 偏移；[columnIndex] 范围为 0..columns.size。
     * columns.size 表示行尾（不含段末换行）。缩进占坐标但不接受装饰。
     */
    fun columnOffset(line: TextLine, columnIndex: Int): Int = columnOffsets(line)[columnIndex]

    private fun columnOffsets(line: TextLine): List<Int> =
        line.columns.runningFold(line.chapterPosition) { position, column ->
            position + columnTextLength(column)
        }

    private fun columnTextLength(column: BaseColumn): Int =
        if (column is TextColumn) column.charData.length else 1
}
