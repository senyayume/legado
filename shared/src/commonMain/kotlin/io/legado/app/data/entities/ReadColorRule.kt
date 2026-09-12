package io.legado.app.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** 阅读区关键词、成对符号和正则着色规则。空 bookUrl 表示全局规则。 */
@Entity(
    tableName = "read_color_rules",
    indices = [Index(value = ["bookUrl", "keyword"])]
)
data class ReadColorRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookUrl: String = "",
    val keyword: String = "",
    val foregroundColor: Int? = null,
    val backgroundColor: Int? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "'keyword'")
    val ruleType: String = TYPE_KEYWORD,
    @ColumnInfo(defaultValue = "''")
    val pattern: String = "",
    @ColumnInfo(defaultValue = "''")
    val pairLeft: String = "",
    @ColumnInfo(defaultValue = "''")
    val pairRight: String = "",
    val contentForegroundColor: Int? = null,
    val contentBackgroundColor: Int? = null,
    val underline: Boolean? = null,
    val bold: Boolean? = null,
    val contentUnderline: Boolean? = null,
    val contentBold: Boolean? = null,
    @ColumnInfo(defaultValue = "1")
    val contentEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val caseSensitive: Boolean = false,
    val chapterStart: Int? = null,
    val chapterEnd: Int? = null,
    @ColumnInfo(defaultValue = "'[]'")
    val excludedChapterRanges: String = "[]",
    val presetId: String? = null
) {

    companion object {
        const val TYPE_KEYWORD = "keyword"
        const val TYPE_PAIR = "pair"
        const val TYPE_REGEX = "regex"
        const val TYPE_PRESET = "preset"
    }
}
