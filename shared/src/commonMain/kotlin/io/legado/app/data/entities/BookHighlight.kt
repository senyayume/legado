package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.legado.app.utils.systemCurrentTimeMillis

/** 用户在书籍章节中保存的一段手动高亮，坐标采用半开区间。 */
@Entity(
    tableName = "highlights",
    indices = [
        Index(value = ["bookUrl", "chapterIndex", "chapterPos"]),
        Index(
            value = ["bookUrl", "chapterIndex", "chapterPos", "chapterPosEnd"],
            unique = true
        )
    ]
)
data class BookHighlight(
    @PrimaryKey
    val time: Long = systemCurrentTimeMillis(),
    val bookUrl: String = "",
    val bookName: String = "",
    val bookAuthor: String = "",
    val chapterIndex: Int = 0,
    val chapterPos: Int = 0,
    val chapterPosEnd: Int = 0,
    val chapterName: String = "",
    val bookText: String = "",
    val foregroundColor: Int? = null,
    val backgroundColor: Int? = null
)
