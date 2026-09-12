package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Transaction
import io.legado.app.data.entities.ReadColorRule
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadColorRuleDao {

    /** Room owns the transaction; command semantics stay in ColorRuleRepository. */
    @Transaction
    suspend fun inTransaction(block: suspend () -> Unit) {
        block()
    }

    @Query(
        """
        select * from read_color_rules
        where bookUrl = '' or bookUrl = :bookUrl
        order by bookUrl desc, sortOrder, id
        """
    )
    suspend fun getForBook(bookUrl: String): List<ReadColorRule>

    @Query(
        """
        select * from read_color_rules
        where bookUrl = '' or bookUrl = :bookUrl
        order by bookUrl desc, sortOrder, id
        """
    )
    fun flowForBook(bookUrl: String): Flow<List<ReadColorRule>>

    @Query("select * from read_color_rules order by bookUrl desc, sortOrder, id")
    suspend fun getAll(): List<ReadColorRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: ReadColorRule): Long

    @Update
    suspend fun update(rule: ReadColorRule)

    @Update
    suspend fun updateAll(rules: List<ReadColorRule>)

    @Delete
    suspend fun delete(rule: ReadColorRule)
}
