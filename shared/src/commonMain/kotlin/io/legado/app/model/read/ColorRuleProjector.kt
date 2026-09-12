package io.legado.app.model.read

data class ColorRuleColumn(val length: Int, val isContent: Boolean)

/** 把视觉列映射回章节偏移，缩进列不推进正文位置。 */
object ColorRuleProjector {
    fun columnPositions(startPosition: Int, columns: List<ColorRuleColumn>): List<Int> {
        var position = startPosition
        return columns.map { column ->
            val current = position
            if (column.isContent) position += column.length
            current
        }
    }
}
