package io.legado.app.data

import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReaderDatabaseMigrationTest {

    @Test
    fun completeOriginalQuickjsV87UpgradesToV91ThroughRoomWithoutDataLoss() = runBlocking {
        verifyRoomUpgradeToV91(readFixture("quickjs-v87.json").getValue("database").jsonObject, 87)
    }

    @Test
    fun completeUpstreamV87UpgradesToV91ThroughRoomWithoutDataLoss() = runBlocking {
        verifyRoomUpgradeToV91(projectSchema(87), 87)
    }

    @Test
    fun completeOriginalQuickjsV90UpgradesToV91ThroughRoomWithoutDataLoss() = runBlocking {
        verifyRoomUpgradeToV91(readFixture("quickjs-v90.json").getValue("database").jsonObject, 90)
    }

    @Test
    fun completeSharedV90UpgradesToV91ThroughRoomWithoutDataLoss() = runBlocking {
        verifyRoomUpgradeToV91(readFixture("shared-v90.json").getValue("database").jsonObject, 90)
    }

    @Test
    fun interruptedV90ToV91MigrationRollsBackIndicesVersionAndAllData() = runBlocking {
        verifyFailedRoomUpgradeRollsBack(interruptMigration = true)
    }

    @Test
    fun invalidSchemaAfterV90ToV91MigrationRollsBackIndicesVersionAndAllData() = runBlocking {
        verifyFailedRoomUpgradeRollsBack(interruptMigration = false)
    }

    @Test
    fun completeUpstreamV87SchemaMatchesV90AndPreservesBusinessData() {
        verifyCompleteSchemaMigration(legacyRules = false)
    }

    @Test
    fun completeV87WithOriginalQuickjsRulesMatchesV90AndPreservesBusinessData() {
        verifyCompleteSchemaMigration(legacyRules = true)
    }

    @Test
    fun upstreamV87WithoutColorRulesGetsReaderTables() {
        withConnection { connection ->
            connection.execSQL(
                """
                    CREATE TABLE books (
                        bookUrl TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent()
            )

            migrateToV90(connection)

            val colorColumns = tableColumns(connection, "read_color_rules")
            assertEquals(
                listOf(
                    "id", "bookUrl", "keyword", "foregroundColor", "backgroundColor",
                    "enabled", "sortOrder", "ruleType", "pattern", "pairLeft", "pairRight",
                    "contentForegroundColor", "contentBackgroundColor", "underline", "bold",
                    "contentUnderline", "contentBold", "contentEnabled", "caseSensitive",
                    "chapterStart", "chapterEnd", "excludedChapterRanges", "presetId"
                ),
                colorColumns
            )
            assertTrue(tableExists(connection, "highlights"))
            assertTrue(indexExists(connection, "index_highlights_bookUrl_chapterIndex_chapterPos_chapterPosEnd"))
        }
    }

    @Test
    fun legacyV87ColorRuleKeepsValuesAndDuplicateHighlightsCollapseToOldest() {
        withConnection { connection ->
            connection.execSQL(
                """
                    CREATE TABLE read_color_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookUrl TEXT NOT NULL,
                        keyword TEXT NOT NULL,
                        foregroundColor INTEGER,
                        backgroundColor INTEGER,
                        enabled INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                """.trimIndent()
            )
            connection.execSQL(
                """
                    INSERT INTO read_color_rules
                    (id, bookUrl, keyword, foregroundColor, backgroundColor, enabled, sortOrder)
                    VALUES (7, 'book://one', '关键', 16711680, 255, 1, 3)
                """.trimIndent()
            )

            DatabaseMigrationsData.migrate87To88(connection)
            DatabaseMigrationsData.migrate88To89(connection)
            // 旧 Android v89 存在同名非唯一索引，升级必须替换它。
            connection.execSQL(
                "CREATE INDEX index_highlights_bookUrl_chapterIndex_chapterPos_chapterPosEnd " +
                    "ON highlights(bookUrl, chapterIndex, chapterPos, chapterPosEnd)"
            )
            connection.execSQL(
                """
                    INSERT INTO highlights
                    (time, bookUrl, bookName, bookAuthor, chapterIndex, chapterPos, chapterPosEnd,
                     chapterName, bookText, foregroundColor, backgroundColor)
                    VALUES
                    (20, 'book://one', '书', '作', 2, 10, 14, '章', '文本', 1, 2),
                    (10, 'book://one', '书', '作', 2, 10, 14, '章', '文本', 3, 4),
                    (30, 'book://one', '书', '作', 2, 11, 14, '章', '文本', 5, 6)
                """.trimIndent()
            )
            DatabaseMigrationsData.migrate89To90(connection)

            val rule = queryOne(connection, "SELECT * FROM read_color_rules WHERE id = 7")
            assertNotNull(rule)
            assertEquals("book://one", rule["bookUrl"])
            assertEquals("关键", rule["keyword"])
            assertEquals(16711680L, rule["foregroundColor"])
            assertEquals(255L, rule["backgroundColor"])
            assertEquals(1L, rule["enabled"])
            assertEquals(3L, rule["sortOrder"])
            assertEquals("keyword", rule["ruleType"])
            assertEquals("[]", rule["excludedChapterRanges"])

            assertEquals(2L, scalarLong(connection, "SELECT count(*) FROM highlights"))
            assertEquals(10L, scalarLong(connection, "SELECT time FROM highlights WHERE chapterPos = 10"))
            assertTrue(indexExists(connection, "index_highlights_bookUrl_chapterIndex_chapterPos"))
            assertTrue(indexExists(connection, "index_highlights_bookUrl_chapterIndex_chapterPos_chapterPosEnd"))
            assertEquals(
                1L,
                scalarLong(connection,
                    "SELECT \"unique\" FROM pragma_index_list('highlights') " +
                        "WHERE name = 'index_highlights_bookUrl_chapterIndex_chapterPos_chapterPosEnd'")
            )
        }
    }

    private val redundantIndices = listOf(
        "index_book_sources_bookSourceUrl",
        "index_replace_rules_id",
        "index_search_keywords_word",
        "index_cookies_url",
        "index_caches_key",
    )

    private val physicalTablesSql = "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name IN " +
        "('chapters', 'search_keywords', 'cookies', 'readRecord', 'caches') ORDER BY name"

    private fun projectSchema(version: Int): JsonObject =
        readSchema(schemaRoot().resolve("shared/schemas/io.legado.app.data.AppDatabase/$version.json"))

    private suspend fun verifyRoomUpgradeToV91(source: JsonObject, sourceVersion: Int) {
        assertEquals(sourceVersion.toString(), source.getValue("version").jsonPrimitive.content)
        val target = projectSchema(91)
        assertEquals("91", target.getValue("version").jsonPrimitive.content)
        withFixtureFile { file ->
            val before = BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                createSchema(connection, source)
                insertMigrationSentinels(connection)
                connection.execSQL("PRAGMA user_version = $sourceVersion")
                snapshotData(connection) to queryRows(connection, physicalTablesSql)
            }
            val hadRules = before.first.containsKey("read_color_rules")
            val database = openRoom(file, DatabaseMigrations.migrations)
            try {
                // 查询迫使 Room 实际打开数据库、执行迁移并验证生成合同。
                val rules = withTimeout(30_000) { database.readColorRuleDao.getAll() }
                assertEquals(if (hadRules) 1 else 0, rules.size)
                if (hadRules) assertEquals("preserved-keyword", rules.single().keyword)
            } finally {
                database.close()
            }
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                assertStoredColumnsPreserved(before.first, connection)
                assertEquals(91L, scalarLong(connection, "PRAGMA user_version"))
                assertEquals(target.getValue("identityHash").jsonPrimitive.content,
                    queryOne(connection, "SELECT identity_hash FROM room_master_table WHERE id = 42")["identity_hash"])
                redundantIndices.forEach { index ->
                    assertTrue("Redundant index must be removed: $index", !indexExists(connection, index))
                }
                withConnection { expected ->
                    createSchema(expected, target)
                    assertSchemaMatches(expected, connection)
                }
                assertEquals("Logical normalization must not rebuild the five physical tables",
                    before.second, queryRows(connection, physicalTablesSql))
                before.second.filter {
                    !(it["sql"] as String).contains("WITHOUT ROWID", ignoreCase = true)
                }.forEach {
                    connection.prepare("SELECT rowid FROM `${it["name"]}` LIMIT 1").use { statement ->
                        assertTrue("ROWID table sentinel must survive", statement.step())
                    }
                }
                if (!hadRules) assertEquals(0L, scalarLong(connection, "SELECT count(*) FROM read_color_rules"))
                if (!before.first.containsKey("highlights")) {
                    assertEquals(0L, scalarLong(connection, "SELECT count(*) FROM highlights"))
                }
                assertEquals(listOf(mapOf("integrity_check" to "ok")),
                    queryRows(connection, "PRAGMA integrity_check"))
                assertTrue(queryRows(connection, "PRAGMA foreign_key_check").isEmpty())
            }
            // 第二次打开也必须通过更新后的 identity，且不重复迁移或写入记录。
            val reopened = openRoom(file, DatabaseMigrations.migrations)
            try {
                assertEquals(if (hadRules) 1 else 0,
                    withTimeout(30_000) { reopened.readColorRuleDao.getAll() }.size)
            } finally {
                reopened.close()
            }
        }
    }

    private suspend fun verifyFailedRoomUpgradeRollsBack(interruptMigration: Boolean) {
        val source = readFixture("quickjs-v90.json").getValue("database").jsonObject
        val actualMigration = DatabaseMigrations.migrations.single {
            it.startVersion == 90 && it.endVersion == 91
        }
        withFixtureFile { file ->
            val before = BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                createSchema(connection, source)
                insertMigrationSentinels(connection)
                connection.execSQL("PRAGMA user_version = 90")
                if (!interruptMigration) {
                    connection.execSQL("ALTER TABLE books ADD COLUMN unexpectedFixtureColumn TEXT")
                    connection.execSQL("UPDATE books SET unexpectedFixtureColumn = 'preserve-on-rejection'")
                }
                redundantIndices.forEach { assertTrue("Source must contain $it", indexExists(connection, it)) }
                snapshotData(connection) to queryRows(connection,
                    "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name")
            }
            var normalizationReached = false
            val probe = object : Migration(90, 91) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    actualMigration.migrate(connection)
                    redundantIndices.forEach {
                        assertTrue("Migration must remove $it before rollback is tested", !indexExists(connection, it))
                    }
                    normalizationReached = true
                    if (interruptMigration) throw FixtureMigrationInterrupted()
                }
            }
            val migrations = DatabaseMigrations.migrations.map {
                if (it.startVersion == 90 && it.endVersion == 91) probe else it
            }.toTypedArray()
            val database = openRoom(file, migrations)
            try {
                val failure = runCatching {
                    withTimeout(30_000) { database.readColorRuleDao.getAll() }
                }.exceptionOrNull()
                assertNotNull("Failed migration must not be accepted", failure)
                assertTrue("Must reach index normalization, not fail during driver initialization", normalizationReached)
                val causes = generateSequence(failure) { it.cause }.toList()
                if (interruptMigration) {
                    assertTrue("Expected injected interruption, got $failure",
                        causes.any { it is FixtureMigrationInterrupted })
                } else {
                    val messages = causes.joinToString("\n") { it.message.orEmpty() }
                    assertTrue("Expected Room books schema validation failure, got $messages",
                        causes.any { it is IllegalStateException } &&
                            messages.contains("books") && messages.contains("unexpectedFixtureColumn"))
                }
            } finally {
                database.close()
            }
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                assertEquals("All existing rows must roll back", before.first, snapshotData(connection))
                assertEquals("All schema definitions, including dropped indices, must roll back", before.second,
                    queryRows(connection, "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name"))
                assertEquals(90L, scalarLong(connection, "PRAGMA user_version"))
                assertEquals(source.getValue("identityHash").jsonPrimitive.content,
                    queryOne(connection, "SELECT identity_hash FROM room_master_table WHERE id = 42")["identity_hash"])
                redundantIndices.forEach { assertTrue("Index must be restored: $it", indexExists(connection, it)) }
            }
        }
    }

    private class FixtureMigrationInterrupted : IllegalStateException("Injected interruption after index normalization")

    private fun openRoom(file: File, migrations: Array<Migration>): AppDatabase =
        Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*migrations)
            .build()

    private suspend fun withFixtureFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("reader-room-migration").toFile()
        try {
            block(directory.resolve("fixture.db"))
        } finally {
            check(directory.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
            check(directory.deleteRecursively()) { "Could not clean fixture directory: $directory" }
        }
    }

    private fun snapshotData(connection: SQLiteConnection): Map<String, List<Map<String, Any?>>> =
        queryRows(connection, "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table' ORDER BY name").associate { row ->
            val table = row.getValue("name") as String
            val primaryKey = queryRows(connection, "PRAGMA table_info('$table')")
                .filter { (it["pk"] as Long) > 0L }.sortedBy { it["pk"] as Long }
                .joinToString(", ") { "`${it["name"]}`" }
            table to queryRows(connection, "SELECT * FROM `$table`" +
                if (primaryKey.isEmpty()) "" else " ORDER BY $primaryKey")
        }

    private fun assertStoredColumnsPreserved(
        before: Map<String, List<Map<String, Any?>>>,
        connection: SQLiteConnection,
    ) {
        val after = snapshotData(connection)
        before.forEach { (table, oldRows) ->
            val newRows = after.getValue(table)
            assertEquals("$table row count", oldRows.size, newRows.size)
            oldRows.zip(newRows).forEach { (oldRow, newRow) ->
                oldRow.forEach { (column, value) ->
                    assertEquals("$table.$column must survive migration", value, newRow[column])
                }
            }
        }
    }

    private fun insertMigrationSentinels(connection: SQLiteConnection) {
        insertBusinessSentinels(connection)
        connection.execSQL(
            """
                INSERT INTO book_sources
                (bookSourceUrl, bookSourceName, bookSourceType, lastUpdateTime, respondTime, weight)
                VALUES ('source://sentinel', 'Saved source', 0, 123, 456, 7)
            """.trimIndent()
        )
        connection.execSQL(
            """
                INSERT INTO replace_rules (id, name, pattern, replacement, isEnabled)
                VALUES (17, 'Saved replacement', 'before', 'after', 0)
            """.trimIndent()
        )
        connection.execSQL(
            """
                INSERT INTO chapters (url, title, isVolume, bookUrl, `index`, isVip, isPay, variable)
                VALUES ('chapter://sentinel', 'Saved chapter', 0, 'book://sentinel', 12, 1, 0, '{"offset":345}')
            """.trimIndent()
        )
        connection.execSQL("INSERT INTO search_keywords (word, usage, lastUseTime) VALUES ('sentinel', 7, 123456)")
        connection.execSQL("INSERT INTO cookies (url, cookie) VALUES ('https://fixture.invalid', 'fixture=preserved')")
        connection.execSQL(
            "INSERT INTO readRecord (bookName, day, startSec, endSec) VALUES ('Migration sentinel', 20260905, 100, 160)"
        )
        connection.execSQL("INSERT INTO caches (`key`, value, deadline) VALUES ('fixture-key', 'saved-value', 987654321)")
        if (tableExists(connection, "read_color_rules")) {
            connection.execSQL(
                """
                    INSERT INTO read_color_rules
                    (id, bookUrl, keyword, foregroundColor, backgroundColor, enabled, sortOrder)
                    VALUES (7, 'book://sentinel', 'preserved-keyword', 16711680, 255, 0, 3)
                """.trimIndent()
            )
            if ("ruleType" in tableColumns(connection, "read_color_rules")) {
                connection.execSQL(
                    """
                        UPDATE read_color_rules SET pattern = 'saved-pattern', pairLeft = '[', pairRight = ']',
                        contentForegroundColor = 3, contentBackgroundColor = 4, underline = 1, bold = 0,
                        contentUnderline = 0, contentBold = 1, contentEnabled = 0, caseSensitive = 1,
                        chapterStart = 2, chapterEnd = 20, excludedChapterRanges = '[[5,6]]', presetId = 'saved-preset'
                        WHERE id = 7
                    """.trimIndent()
                )
            }
        }
        if (tableExists(connection, "highlights")) {
            connection.execSQL(
                """
                    INSERT INTO highlights
                    (time, bookUrl, bookName, bookAuthor, chapterIndex, chapterPos, chapterPosEnd,
                     chapterName, bookText, foregroundColor, backgroundColor)
                    VALUES
                    (201, 'book://sentinel', 'Migration sentinel', 'Fixture author', 12, 3, 6,
                     'Saved chapter', 'cat', 7, 9),
                    (202, 'book://sentinel', 'Migration sentinel', 'Fixture author', 12, 10, 13,
                     'Saved chapter', 'dog', NULL, 8)
                """.trimIndent()
            )
        }
    }

    private fun verifyCompleteSchemaMigration(legacyRules: Boolean) {
        val root = schemaRoot()
        val schemas = root.resolve("shared/schemas/io.legado.app.data.AppDatabase")
        val upstream = readSchema(schemas.resolve("87.json"))
        val target = readFixture("shared-v90.json").getValue("database").jsonObject
        assertEquals("87", upstream.getValue("version").jsonPrimitive.content)
        assertEquals("90", target.getValue("version").jsonPrimitive.content)

        withConnection { actual ->
            createSchema(actual, upstream)
            if (legacyRules) {
                val ruleEntity = readFixture("quickjs-v87-read-color-rules.json")
                assertEquals("read_color_rules", ruleEntity.getValue("tableName").jsonPrimitive.content)
                assertTrue(!tableExists(actual, "read_color_rules"))
                createEntity(actual, ruleEntity)
                actual.execSQL(
                    """
                        INSERT INTO read_color_rules
                        (id, bookUrl, keyword, foregroundColor, backgroundColor, enabled, sortOrder)
                        VALUES (7, 'book://sentinel', 'preserved-keyword', 16711680, 255, 0, 3)
                    """.trimIndent()
                )
            }
            insertBusinessSentinels(actual)
            val businessTables = listOf("books", "bookmarks", "book_groups")
            val before = businessTables.associateWith { queryRows(actual, "SELECT * FROM `$it`") }
            val legacyRule = if (legacyRules) {
                queryOne(actual, "SELECT * FROM read_color_rules WHERE id = 7")
            } else null

            migrateToV90(actual)

            businessTables.forEach { table ->
                assertEquals("$table data must survive migration", before.getValue(table),
                    queryRows(actual, "SELECT * FROM `$table`"))
            }
            if (legacyRule != null) {
                val migratedRule = queryOne(actual, "SELECT * FROM read_color_rules WHERE id = 7")
                legacyRule.forEach { (column, value) ->
                    assertEquals("Legacy rule column $column", value, migratedRule[column])
                }
                assertEquals(1L, scalarLong(actual, "SELECT count(*) FROM read_color_rules"))
                assertEquals("keyword", migratedRule["ruleType"])
                assertEquals("[]", migratedRule["excludedChapterRanges"])
            }
            withConnection { expected ->
                createSchema(expected, target)
                assertSchemaMatches(expected, actual)
            }
            assertEquals(listOf(mapOf("integrity_check" to "ok")), queryRows(actual, "PRAGMA integrity_check"))
            assertTrue(queryRows(actual, "PRAGMA foreign_key_check").isEmpty())
        }
    }

    private fun schemaRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("shared/schemas/io.legado.app.data.AppDatabase/87.json").isFile }
            ?: error("Cannot locate the shared v87 schema from the test working directory")

    private fun readSchema(file: File): JsonObject {
        check(file.isFile) {
            "Required project schema missing: ${file.absolutePath}"
        }
        return Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            .getValue("database").jsonObject
    }

    private fun readFixture(name: String): JsonObject {
        val path = "/reader-migration/$name"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) {
            "Required classpath fixture missing: $path"
        }
        return stream.bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }

    private fun createSchema(connection: androidx.sqlite.SQLiteConnection, schema: JsonObject) {
        schema.getValue("entities").jsonArray.forEach { createEntity(connection, it.jsonObject) }
        schema.getValue("views").jsonArray.forEach { element ->
            val view = element.jsonObject
            connection.execSQL(
                view.getValue("createSql").jsonPrimitive.content.replace(
                    "\${VIEW_NAME}", view.getValue("viewName").jsonPrimitive.content
                )
            )
        }
        schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
    }

    private fun createEntity(connection: androidx.sqlite.SQLiteConnection, entity: JsonObject) {
        val table = entity.getValue("tableName").jsonPrimitive.content
        connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        entity["indices"]?.jsonArray?.forEach { index ->
            connection.execSQL(
                index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
            )
        }
    }

    private fun insertBusinessSentinels(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL(
            """
                INSERT INTO books
                (bookUrl, name, author, durChapterIndex, durChapterPos, durChapterTime,
                 totalChapterNum, canUpdate, `group`, variable, readConfig)
                VALUES ('book://sentinel', 'Migration sentinel', 'Fixture author', 12, 345, 987654321,
                        89, 0, 8, '{"token":"keep"}', '{"imageStyle":"TEXT"}')
            """.trimIndent()
        )
        connection.execSQL(
            """
                INSERT INTO bookmarks
                (time, bookName, bookAuthor, chapterIndex, chapterPos, chapterName, bookText, content)
                VALUES (123, 'Migration sentinel', 'Fixture author', 12, 345,
                        'Chapter twelve', 'Bookmarked original text', 'User note')
            """.trimIndent()
        )
        connection.execSQL(
            """
                INSERT INTO book_groups (groupId, groupName, cover, `order`, enableRefresh, `show`, bookSort)
                VALUES (8, 'Saved group', 'cover://sentinel', 4, 0, 1, 2)
            """.trimIndent()
        )
    }

    private fun assertSchemaMatches(
        expected: androidx.sqlite.SQLiteConnection,
        actual: androidx.sqlite.SQLiteConnection,
    ) {
        // room_master_table 的 identity_hash 由 Room 打开流程更新，不是这些 SQL migration 的职责。
        val tablesSql = "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table' ORDER BY name"
        val tables = queryRows(expected, tablesSql)
        assertEquals("Complete table set", tables, queryRows(actual, tablesSql))
        tables.forEach { row ->
            val table = row.getValue("name") as String
            val columnsSql = "PRAGMA table_xinfo('$table')"
            fun columns(connection: androidx.sqlite.SQLiteConnection) =
                queryRows(connection, columnsSql).associate { it.getValue("name") to (it - "cid") }
            assertEquals("$table fields/types/nullability/defaults/primary key", columns(expected), columns(actual))
            assertEquals("$table foreign keys",
                queryRows(expected, "PRAGMA foreign_key_list('$table')"),
                queryRows(actual, "PRAGMA foreign_key_list('$table')"))

            fun indices(connection: androidx.sqlite.SQLiteConnection) =
                // 主键由 table_xinfo 核验；ROWID/WITHOUT ROWID 的内部索引载荷不是逻辑迁移合同。
                queryRows(connection, "PRAGMA index_list('$table')").filter { it["origin"] == "c" }.associate { index ->
                    val name = index.getValue("name") as String
                    val keyColumns = queryRows(connection, "PRAGMA index_xinfo('$name')")
                        .filter { it["key"] == 1L }.map { it - "cid" }
                    name to ((index - "seq") + ("columns" to keyColumns))
                }
            assertEquals("$table index names/uniqueness/columns/order/collation", indices(expected), indices(actual))
        }
        val viewsSql = "SELECT name, sql FROM sqlite_master WHERE type = 'view' ORDER BY name"
        assertEquals("Complete view definitions", queryRows(expected, viewsSql), queryRows(actual, viewsSql))
    }

    private fun queryRows(
        connection: androidx.sqlite.SQLiteConnection,
        sql: String,
    ): List<Map<String, Any?>> = connection.prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add(statement.getColumnNames().mapIndexed { index, column ->
                    column to when (statement.getColumnType(index)) {
                        1 -> statement.getLong(index)
                        2 -> statement.getDouble(index)
                        3 -> statement.getText(index)
                        4 -> statement.getBlob(index).toList()
                        else -> null
                    }
                }.toMap())
            }
        }
    }

    private fun migrateToV90(connection: androidx.sqlite.SQLiteConnection) {
        DatabaseMigrationsData.migrate87To88(connection)
        DatabaseMigrationsData.migrate88To89(connection)
        DatabaseMigrationsData.migrate89To90(connection)
    }

    private fun withConnection(block: (androidx.sqlite.SQLiteConnection) -> Unit) {
        BundledSQLiteDriver().open(":memory:").use(block)
    }

    private fun tableExists(connection: androidx.sqlite.SQLiteConnection, table: String): Boolean =
        scalarLong(
            connection,
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"
        ) == 1L

    private fun indexExists(connection: androidx.sqlite.SQLiteConnection, index: String): Boolean =
        scalarLong(
            connection,
            "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = '$index'"
        ) == 1L

    private fun tableColumns(connection: androidx.sqlite.SQLiteConnection, table: String): List<String> {
        val result = mutableListOf<String>()
        connection.prepare("PRAGMA table_info('$table')").use { statement ->
            while (statement.step()) result += statement.getText(1)
        }
        return result
    }

    private fun scalarLong(connection: androidx.sqlite.SQLiteConnection, sql: String): Long =
        connection.prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.getLong(0)
        }

    private fun queryOne(
        connection: androidx.sqlite.SQLiteConnection,
        sql: String
    ): Map<String, Any?> = connection.prepare(sql).use { statement ->
        assertTrue(statement.step())
        statement.getColumnNames().associateWith { column ->
            val index = statement.getColumnNames().indexOf(column)
            when (statement.getColumnType(index)) {
                1 -> statement.getLong(index)
                2, 3 -> statement.getText(index)
                else -> null
            }
        }
    }
}
