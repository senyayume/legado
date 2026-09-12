package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import io.legado.app.data.entities.BookHighlight

@Dao
interface BookHighlightDao {

    @Query(
        """
        select * from highlights
        where bookUrl = :bookUrl
        order by chapterIndex, chapterPos, time
        """
    )
    suspend fun getByBook(bookUrl: String): List<BookHighlight>

    @Query(
        """
        select * from highlights
        where bookUrl = :bookUrl and chapterIndex = :chapterIndex
        order by chapterPos, time
        """
    )
    suspend fun getByChapter(bookUrl: String, chapterIndex: Int): List<BookHighlight>

    @Query(
        """
        select * from highlights
        where bookUrl = :bookUrl
          and chapterIndex = :chapterIndex
          and chapterPos = :chapterPos
          and chapterPosEnd = :chapterPosEnd
        limit 1
        """
    )
    suspend fun findExact(
        bookUrl: String,
        chapterIndex: Int,
        chapterPos: Int,
        chapterPosEnd: Int
    ): BookHighlight?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(highlight: BookHighlight): Long

    @Delete
    suspend fun delete(highlight: BookHighlight)
}
