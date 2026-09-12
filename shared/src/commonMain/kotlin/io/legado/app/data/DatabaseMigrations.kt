package io.legado.app.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.legado.app.data.entities.ReadRecord
import io.legado.app.utils.midnightSecFromDayKey
import io.legado.app.utils.prevDayKey
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 共享数据库的显式迁移；各平台只适配 Migration 的调用签名。
 *
 * 调用方 (各平台 `Room.databaseBuilder(...).addMigrations(*DatabaseMigrations.migrations)`) 签名不变。
 */
object DatabaseMigrations {

    val migrations: Array<Migration> by lazy {
        arrayOf(
            migration80To81(), migration81To82(), migration82To83(),
            migration87To88(), migration88To89(), migration89To90(), migration90To91()
        )
    }

}

/**
 * 手写迁移体 (纯 SQL / 纯逻辑, 无平台差异)。
 *
 * 时间换算走 [midnightSecFromDayKey] / [prevDayKey] / [systemCurrentTimeMillis] expect
 * (JVM 半区 actual 就是原来的 java.util.Calendar 代码), 各平台 Migration 子类只做一行委托。
 */
object DatabaseMigrationsData {

    /** v87 规则表兼容迁移：上游 v87 无表，旧 quickjs v87 只有基础七列。 */
    fun migrate87To88(connection: SQLiteConnection) {
        if (!tableExists(connection, "read_color_rules")) {
            connection.execSQL(
                """
                    CREATE TABLE read_color_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookUrl TEXT NOT NULL,
                        keyword TEXT NOT NULL,
                        foregroundColor INTEGER,
                        backgroundColor INTEGER,
                        enabled INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        ruleType TEXT NOT NULL DEFAULT 'keyword',
                        pattern TEXT NOT NULL DEFAULT '',
                        pairLeft TEXT NOT NULL DEFAULT '',
                        pairRight TEXT NOT NULL DEFAULT '',
                        contentForegroundColor INTEGER,
                        contentBackgroundColor INTEGER,
                        underline INTEGER,
                        bold INTEGER,
                        contentUnderline INTEGER,
                        contentBold INTEGER,
                        contentEnabled INTEGER NOT NULL DEFAULT 1,
                        caseSensitive INTEGER NOT NULL DEFAULT 0,
                        chapterStart INTEGER,
                        chapterEnd INTEGER,
                        excludedChapterRanges TEXT NOT NULL DEFAULT '[]',
                        presetId TEXT
                    )
                """.trimIndent()
            )
        } else {
            val columns = tableColumns(connection, "read_color_rules")
            val additions = listOf(
                "ruleType TEXT NOT NULL DEFAULT 'keyword'",
                "pattern TEXT NOT NULL DEFAULT ''",
                "pairLeft TEXT NOT NULL DEFAULT ''",
                "pairRight TEXT NOT NULL DEFAULT ''",
                "contentForegroundColor INTEGER",
                "contentBackgroundColor INTEGER",
                "underline INTEGER",
                "bold INTEGER",
                "contentUnderline INTEGER",
                "contentBold INTEGER",
                "contentEnabled INTEGER NOT NULL DEFAULT 1",
                "caseSensitive INTEGER NOT NULL DEFAULT 0",
                "chapterStart INTEGER",
                "chapterEnd INTEGER",
                "excludedChapterRanges TEXT NOT NULL DEFAULT '[]'",
                "presetId TEXT"
            )
            additions.forEach { definition ->
                val name = definition.substringBefore(' ')
                if (name !in columns) {
                    connection.execSQL("ALTER TABLE read_color_rules ADD COLUMN $definition")
                }
            }
        }
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_read_color_rules_bookUrl_keyword " +
                "ON read_color_rules (bookUrl, keyword)"
        )
    }

    /** v88 新增手动高亮表及按书籍章节定位的查询索引。 */
    fun migrate88To89(connection: SQLiteConnection) {
        connection.execSQL(
            """
                CREATE TABLE IF NOT EXISTS highlights (
                    time INTEGER NOT NULL PRIMARY KEY,
                    bookUrl TEXT NOT NULL,
                    bookName TEXT NOT NULL,
                    bookAuthor TEXT NOT NULL,
                    chapterIndex INTEGER NOT NULL,
                    chapterPos INTEGER NOT NULL,
                    chapterPosEnd INTEGER NOT NULL,
                    chapterName TEXT NOT NULL,
                    bookText TEXT NOT NULL,
                    foregroundColor INTEGER,
                    backgroundColor INTEGER
                )
            """.trimIndent()
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_highlights_bookUrl_chapterIndex_chapterPos " +
                "ON highlights (bookUrl, chapterIndex, chapterPos)"
        )
    }

    /** v89 清理历史重复区间，再建立 v90 唯一索引。 */
    fun migrate89To90(connection: SQLiteConnection) {
        if (!tableExists(connection, "highlights")) return
        connection.execSQL(
            """
                DELETE FROM highlights
                WHERE EXISTS (
                    SELECT 1 FROM highlights older
                    WHERE older.bookUrl = highlights.bookUrl
                      AND older.chapterIndex = highlights.chapterIndex
                      AND older.chapterPos = highlights.chapterPos
                      AND older.chapterPosEnd = highlights.chapterPosEnd
                      AND older.time < highlights.time
                )
            """.trimIndent()
        )
        connection.execSQL(
            "DROP INDEX IF EXISTS index_highlights_bookUrl_chapterIndex_chapterPos_chapterPosEnd"
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX " +
                "index_highlights_bookUrl_chapterIndex_chapterPos_chapterPosEnd " +
                "ON highlights (bookUrl, chapterIndex, chapterPos, chapterPosEnd)"
        )
    }

    fun migrate80To81(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS searchBooks")
    }

    fun migrate81To82(connection: SQLiteConnection) {
        // 旧 readRecord: (deviceId, bookName, readTime累计, lastRead毫秒)
        // 新 readRecord: (bookName, day yyyyMMdd, readTime增量, lastRead毫秒) PK(bookName, day)
        //
        // 迁移策略：按 bookName 聚合，把全部累计时长归到 dayKey(maxLastRead) 那一天。
        // 历史细分数据无法还原，至少保住总时长和"最后阅读日"。

        // 1. 读出聚合后的旧数据
        data class OldRow(val bookName: String, val readTime: Long, val lastRead: Long)

        val rows = mutableListOf<OldRow>()
        connection.prepare(
            "select bookName, sum(readTime), max(lastRead) from readRecord group by bookName"
        ).use { stmt ->
            while (stmt.step()) {
                rows.add(OldRow(stmt.getText(0), stmt.getLong(1), stmt.getLong(2)))
            }
        }

        // 2. 重建表（含 lastRead 列）
        connection.execSQL("DROP TABLE readRecord")
        connection.execSQL(
            """
                CREATE TABLE IF NOT EXISTS readRecord (
                    bookName TEXT NOT NULL,
                    day INTEGER NOT NULL,
                    readTime INTEGER NOT NULL DEFAULT 0,
                    lastRead INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(bookName, day)
                )
                """.trimIndent()
        )

        // 3. 写回
        val now = systemCurrentTimeMillis()
        connection.prepare(
            "INSERT OR REPLACE INTO readRecord(bookName, day, readTime, lastRead) VALUES(?, ?, ?, ?)"
        ).use { insert ->
            for (row in rows) {
                if (row.bookName.isEmpty() || row.readTime <= 0) continue
                val ms = if (row.lastRead > 0) row.lastRead else now
                val day = ReadRecord.dayKey(ms / 1000)
                insert.bindText(1, row.bookName)
                insert.bindInt(2, day)
                insert.bindLong(3, row.readTime)
                insert.bindLong(4, ms)
                insert.step()
                insert.reset()
            }
        }
    }

    fun migrate82To83(connection: SQLiteConnection) {
        data class OldRow(
            val bookName: String,
            val day: Int,
            val readTimeMs: Long,
            val lastReadMs: Long
        )

        val rows = mutableListOf<OldRow>()
        connection.prepare("select bookName, day, readTime, lastRead from readRecord").use { stmt ->
            while (stmt.step()) {
                rows.add(OldRow(stmt.getText(0), stmt.getInt(1), stmt.getLong(2), stmt.getLong(3)))
            }
        }

        connection.execSQL("DROP TABLE readRecord")
        connection.execSQL(
            """CREATE TABLE readRecord (
                    bookName TEXT NOT NULL,
                    day INTEGER NOT NULL,
                    startSec INTEGER NOT NULL,
                    endSec INTEGER NOT NULL,
                    PRIMARY KEY(bookName, day, startSec)
                )""".trimIndent()
        )

        val nowSec = systemCurrentTimeMillis() / 1000
        connection.prepare("INSERT OR IGNORE INTO readRecord VALUES(?,?,?,?)").use { insert ->
            for (row in rows) {
                if (row.bookName.isEmpty() || row.readTimeMs <= 0) continue
                var remaining = row.readTimeMs / 1000
                val endSec0 = if (row.lastReadMs > 0) row.lastReadMs / 1000 else nowSec
                var curDay = row.day
                val curEndSec = endSec0

                val dayStartSec = midnightSecFromDayKey(curDay)
                val maxBack = minOf(16L * 3600, (curEndSec - dayStartSec).coerceAtLeast(0))
                val seg0 = minOf(remaining, maxBack)
                if (seg0 > 0) {
                    insert.bindText(1, row.bookName)
                    insert.bindInt(2, curDay)
                    insert.bindLong(3, curEndSec - seg0)
                    insert.bindLong(4, curEndSec)
                    insert.step()
                    insert.reset()
                    remaining -= seg0
                }

                curDay = prevDayKey(curDay)
                while (remaining > 0) {
                    val winEnd = midnightSecFromDayKey(curDay) + 20L * 3600
                    val seg = minOf(remaining, 16L * 3600)
                    insert.bindText(1, row.bookName)
                    insert.bindInt(2, curDay)
                    insert.bindLong(3, winEnd - seg)
                    insert.bindLong(4, winEnd)
                    insert.step()
                    insert.reset()
                    remaining -= seg
                    curDay = prevDayKey(curDay)
                }
            }
        }
    }

    /**
     * 旧 quickjs 与共享 v90 的实体结构相同，但前者仍有已被主键覆盖的显式索引。
     * 通过正式升级让 Room 校验并更新身份；保留现有表和全部行，不改写 identity。
     */
    fun migrate90To91(connection: SQLiteConnection) {
        connection.execSQL("DROP INDEX IF EXISTS index_book_sources_bookSourceUrl")
        connection.execSQL("DROP INDEX IF EXISTS index_replace_rules_id")
        connection.execSQL("DROP INDEX IF EXISTS index_search_keywords_word")
        connection.execSQL("DROP INDEX IF EXISTS index_cookies_url")
        connection.execSQL("DROP INDEX IF EXISTS index_caches_key")
    }

    private fun tableExists(connection: SQLiteConnection, table: String): Boolean {
        return connection.prepare(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1"
        ).use { statement ->
            statement.bindText(1, table)
            statement.step()
        }
    }

    private fun tableColumns(connection: SQLiteConnection, table: String): Set<String> {
        val result = mutableSetOf<String>()
        connection.prepare("PRAGMA table_info('$table')").use { statement ->
            while (statement.step()) result += statement.getText(1)
        }
        return result
    }
}

/**
 * Migration 子类由各平台源集提供: 官方 Room3 的 `Migration.migrate` 是 suspend,
 * 鸿蒙 CPF fork 的不是 (与 [Migration84To85] 同一处平台差异)。
 *
 * 与 [Migration84To85] 的区别: `AutoMigrationSpec.onPostMigrate` 有默认实现, 故那边能用
 * `expect class ... : AutoMigrationSpec`; 而 `Migration.migrate` 是 abstract, expect class
 * 无法留一个签名随平台变的抽象成员不声明, 故这里用 expect 工厂函数, 三个 actual 只做一行委托。
 */
internal expect fun migration80To81(): Migration

internal expect fun migration81To82(): Migration

internal expect fun migration82To83(): Migration

internal expect fun migration87To88(): Migration

internal expect fun migration88To89(): Migration

internal expect fun migration89To90(): Migration

internal expect fun migration90To91(): Migration
