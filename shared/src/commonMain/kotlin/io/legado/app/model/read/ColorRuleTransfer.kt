package io.legado.app.model.read

import io.legado.app.data.entities.ReadColorRule
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

enum class ColorRuleError {
    INVALID_RULE, INVALID_RANGE, INVALID_ORDER, NOT_FOUND, STORAGE,
    INPUT_TOO_LARGE, UNSUPPORTED_VERSION, INVALID_FILE, FILE_IO, PLATFORM_UNAVAILABLE,
    REFRESH, DUPLICATE
}

class ColorRuleException(
    val reason: ColorRuleError,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

internal suspend fun <T> colorRuleResult(
    fallback: ColorRuleError = ColorRuleError.STORAGE,
    block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: ColorRuleException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(ColorRuleException(fallback, e))
}

@Serializable
data class ColorRuleTransferFile(
    val schemaVersion: Int = ColorRuleTransfer.SCHEMA_VERSION,
    val rules: List<ColorRuleTransferRule> = emptyList(),
)

/** Version-one wire contract; database keys and source book URLs are deliberately absent. */
@Serializable
data class ColorRuleTransferRule(
    val scope: String = ColorRuleTransfer.SCOPE_GLOBAL,
    val ruleType: String = ReadColorRule.TYPE_KEYWORD,
    val keyword: String = "",
    val pattern: String = "",
    val pairLeft: String = "",
    val pairRight: String = "",
    val foregroundColor: Int? = null,
    val backgroundColor: Int? = null,
    val contentForegroundColor: Int? = null,
    val contentBackgroundColor: Int? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val contentEnabled: Boolean = true,
    val caseSensitive: Boolean = false,
    val underline: Boolean? = null,
    val bold: Boolean? = null,
    val contentUnderline: Boolean? = null,
    val contentBold: Boolean? = null,
    val chapterStart: Int? = null,
    val chapterEnd: Int? = null,
    val excludedChapterRanges: String = "[]",
    val presetId: String? = null,
)

data class ColorRuleImportIssue(val index: Int, val reason: ColorRuleError)
data class ColorRuleImportReport(
    val rules: List<ReadColorRule>,
    val skippedCount: Int,
    val errors: List<ColorRuleImportIssue>,
)
data class ColorRuleImportStats(val inserted: Int, val updated: Int, val skipped: Int)

object ColorRuleTransfer {
    const val SCHEMA_VERSION = 1
    const val SCOPE_GLOBAL = "global"
    const val SCOPE_CURRENT_BOOK = "currentBook"
    const val MAX_RULES = 1000
    const val MAX_IMPORT_BYTES = 1024 * 1024
    const val MAX_RANGE_JSON_LENGTH = 1000
    private const val MAX_RANGES = 32
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(rules: List<ReadColorRule>): Result<String> = checked {
        if (rules.size > MAX_RULES) throw ColorRuleException(ColorRuleError.INPUT_TOO_LARGE)
        val text = json.encodeToString(ColorRuleTransferFile(rules = rules.map { rule ->
            ColorRuleTransferRule(
                scope = if (rule.bookUrl.isBlank()) SCOPE_GLOBAL else SCOPE_CURRENT_BOOK,
                ruleType = rule.ruleType, keyword = rule.keyword, pattern = rule.pattern,
                pairLeft = rule.pairLeft, pairRight = rule.pairRight,
                foregroundColor = rule.foregroundColor, backgroundColor = rule.backgroundColor,
                contentForegroundColor = rule.contentForegroundColor,
                contentBackgroundColor = rule.contentBackgroundColor,
                enabled = rule.enabled, sortOrder = rule.sortOrder,
                contentEnabled = rule.contentEnabled, caseSensitive = rule.caseSensitive,
                underline = rule.underline, bold = rule.bold,
                contentUnderline = rule.contentUnderline, contentBold = rule.contentBold,
                chapterStart = rule.chapterStart, chapterEnd = rule.chapterEnd,
                excludedChapterRanges = rule.excludedChapterRanges, presetId = rule.presetId,
            )
        }))
        checkSize(text)
        text
    }

    fun decode(text: String, currentBookUrl: String): Result<ColorRuleImportReport> = checked {
        checkSize(text)
        val file = json.parseToJsonElement(text) as? JsonObject
            ?: throw ColorRuleException(ColorRuleError.INVALID_FILE)
        if ((file["schemaVersion"] as? JsonPrimitive)?.intOrNull != SCHEMA_VERSION) {
            throw ColorRuleException(ColorRuleError.UNSUPPORTED_VERSION)
        }
        val entries = file["rules"] as? JsonArray
            ?: throw ColorRuleException(ColorRuleError.INVALID_FILE)
        val imported = ArrayList<ReadColorRule>()
        val errors = ArrayList<ColorRuleImportIssue>()
        entries.take(MAX_RULES).forEachIndexed { index, element ->
            val result = checked {
                val entry = json.decodeFromJsonElement<ColorRuleTransferRule>(element)
                val bookUrl = when (entry.scope) {
                    SCOPE_GLOBAL -> ""
                    SCOPE_CURRENT_BOOK -> currentBookUrl.takeIf { it.isNotBlank() }
                        ?: throw ColorRuleException(ColorRuleError.INVALID_RULE)
                    else -> throw ColorRuleException(ColorRuleError.INVALID_RULE)
                }
                validate(ReadColorRule(
                    bookUrl = bookUrl, ruleType = entry.ruleType,
                    keyword = entry.keyword, pattern = entry.pattern,
                    pairLeft = entry.pairLeft, pairRight = entry.pairRight,
                    foregroundColor = entry.foregroundColor, backgroundColor = entry.backgroundColor,
                    contentForegroundColor = entry.contentForegroundColor,
                    contentBackgroundColor = entry.contentBackgroundColor,
                    enabled = entry.enabled, sortOrder = entry.sortOrder,
                    contentEnabled = entry.contentEnabled, caseSensitive = entry.caseSensitive,
                    underline = entry.underline, bold = entry.bold,
                    contentUnderline = entry.contentUnderline, contentBold = entry.contentBold,
                    chapterStart = entry.chapterStart, chapterEnd = entry.chapterEnd,
                    excludedChapterRanges = entry.excludedChapterRanges, presetId = entry.presetId,
                )).getOrThrow()
            }
            result.fold(
                onSuccess = { imported += it },
                onFailure = { errors += ColorRuleImportIssue(index + 1,
                    (it as? ColorRuleException)?.reason ?: ColorRuleError.INVALID_RULE) },
            )
        }
        if (entries.size > MAX_RULES) {
            errors += ColorRuleImportIssue(MAX_RULES + 1, ColorRuleError.INPUT_TOO_LARGE)
        }
        ColorRuleImportReport(imported, entries.size - imported.size, errors)
    }

    fun validate(rule: ReadColorRule): Result<ReadColorRule> = checked {
        if (rule.id < 0 || rule.bookUrl.length > 8192 ||
            rule.keyword.length > ColorRuleRepository.MAX_KEYWORD_LENGTH ||
            rule.pairLeft.length > ColorRuleRepository.MAX_KEYWORD_LENGTH ||
            rule.pairRight.length > ColorRuleRepository.MAX_KEYWORD_LENGTH ||
            rule.pattern.length > ColorRuleMatcher.MAX_REGEX_LENGTH ||
            (rule.presetId?.length ?: 0) > 100
        ) throw ColorRuleException(ColorRuleError.INVALID_RULE)
        when (rule.ruleType) {
            ReadColorRule.TYPE_KEYWORD -> if (rule.keyword.isBlank()) {
                throw ColorRuleException(ColorRuleError.INVALID_RULE)
            }
            ReadColorRule.TYPE_PAIR -> if (rule.pairLeft.isBlank() || rule.pairRight.isBlank() ||
                rule.pairLeft == rule.pairRight
            ) throw ColorRuleException(ColorRuleError.INVALID_RULE)
            ReadColorRule.TYPE_REGEX -> {
                if (rule.pattern.isBlank()) throw ColorRuleException(ColorRuleError.INVALID_RULE)
                try { Regex(rule.pattern) } catch (e: IllegalArgumentException) {
                    throw ColorRuleException(ColorRuleError.INVALID_RULE, e)
                }
            }
            // Persisted legacy presets remain importable, without adding a new matching mode.
            ReadColorRule.TYPE_PRESET -> if (rule.keyword.isBlank()) {
                throw ColorRuleException(ColorRuleError.INVALID_RULE)
            }
            else -> throw ColorRuleException(ColorRuleError.INVALID_RULE)
        }
        if ((rule.chapterStart != null && rule.chapterStart < 1) ||
            (rule.chapterEnd != null && rule.chapterEnd < 1) ||
            (rule.chapterStart != null && rule.chapterEnd != null && rule.chapterStart > rule.chapterEnd)
        ) throw ColorRuleException(ColorRuleError.INVALID_RANGE)
        val ranges = normalizeExcludedChapterRanges(rule.excludedChapterRanges)
            ?: throw ColorRuleException(ColorRuleError.INVALID_RANGE)
        rule.copy(excludedChapterRanges = ranges)
    }

    fun normalizeExcludedChapterRanges(value: String): String? {
        if (value.length > MAX_RANGE_JSON_LENGTH) return null
        return runCatching {
            val ranges = json.parseToJsonElement(value.trim().ifBlank { "[]" }) as? JsonArray
                ?: return null
            if (ranges.size > MAX_RANGES) return null
            val normalized = ranges.map { element ->
                val range = element as? JsonArray ?: return null
                if (range.size !in 1..2) return null
                val ints = range.map {
                    val number = it as? JsonPrimitive ?: return null
                    if (number.isString) return null
                    number.intOrNull?.takeIf { n -> n > 0 } ?: return null
                }
                JsonArray(listOf(JsonPrimitive(ints.min()), JsonPrimitive(ints.max())))
            }
            JsonArray(normalized).toString().takeIf { it.length <= MAX_RANGE_JSON_LENGTH }
        }.getOrNull()
    }

    fun equivalentIgnoringId(left: ReadColorRule, right: ReadColorRule) = left.copy(id = 0) == right.copy(id = 0)
    fun sameIdentity(left: ReadColorRule, right: ReadColorRule) =
        left.bookUrl == right.bookUrl && left.ruleType == right.ruleType &&
            left.keyword == right.keyword && left.pattern == right.pattern &&
            left.pairLeft == right.pairLeft && left.pairRight == right.pairRight &&
            left.presetId == right.presetId

    private fun checkSize(text: String) {
        if (text.length > MAX_IMPORT_BYTES || text.encodeToByteArray().size > MAX_IMPORT_BYTES) {
            throw ColorRuleException(ColorRuleError.INPUT_TOO_LARGE)
        }
    }

    private inline fun <T> checked(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: ColorRuleException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(ColorRuleException(ColorRuleError.INVALID_FILE, e))
    }
}
