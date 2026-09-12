package io.legado.app.model.read

import io.legado.app.data.AppDbProviders
import io.legado.app.data.dao.ReadColorRuleDao
import io.legado.app.data.entities.ReadColorRule
import kotlinx.coroutines.flow.Flow

/** The only write owner for explicit reader color rules. */
class ColorRuleRepository(
    private val dao: ReadColorRuleDao = AppDbProviders.get().readColorRuleDao,
) {
    companion object {
        const val MAX_KEYWORD_LENGTH = 100

        fun prepareKeywordRule(requested: ReadColorRule, rules: List<ReadColorRule>): ReadColorRule {
            if (requested.ruleType != ReadColorRule.TYPE_KEYWORD || requested.id != 0L) return requested
            val normalized = requested.copy(keyword = requested.keyword.trim())
            val existing = rules.firstOrNull { ColorRuleTransfer.sameIdentity(it, normalized) }
                ?: return normalized
            return existing.copy(
                foregroundColor = normalized.foregroundColor ?: existing.foregroundColor,
                backgroundColor = normalized.backgroundColor ?: existing.backgroundColor,
            )
        }
    }

    fun flowForBook(bookUrl: String): Flow<List<ReadColorRule>> = dao.flowForBook(bookUrl)
    suspend fun getForBook(bookUrl: String): Result<List<ReadColorRule>> =
        colorRuleResult { dao.getForBook(bookUrl) }

    suspend fun save(rule: ReadColorRule): Result<ReadColorRule> = colorRuleResult {
        var saved = ColorRuleTransfer.validate(rule).getOrThrow()
        dao.inTransaction {
            val existing = dao.getAll()
            if (saved.id == 0L) {
                if (saved.ruleType == ReadColorRule.TYPE_KEYWORD &&
                    existing.any { ColorRuleTransfer.sameIdentity(it, saved) }
                ) throw ColorRuleException(ColorRuleError.DUPLICATE)
                val scope = existing.filter { it.bookUrl == saved.bookUrl }
                saved = saved.copy(sortOrder = nextOrder(scope))
                val id = dao.insert(saved)
                if (id <= 0) throw ColorRuleException(ColorRuleError.STORAGE)
                saved = saved.copy(id = id)
            } else {
                val previous = existing.firstOrNull { it.id == saved.id }
                    ?: throw ColorRuleException(ColorRuleError.NOT_FOUND)
                saved = saved.copy(sortOrder = if (previous.bookUrl == saved.bookUrl) previous.sortOrder
                    else nextOrder(existing.filter { it.bookUrl == saved.bookUrl }))
                dao.update(saved)
            }
        }
        saved
    }

    suspend fun setEnabled(id: Long, enabled: Boolean): Result<Unit> = colorRuleResult {
        dao.inTransaction {
            val rule = find(id)
            dao.update(rule.copy(enabled = enabled))
        }
    }

    suspend fun delete(id: Long): Result<Unit> = colorRuleResult {
        dao.inTransaction { dao.delete(find(id)) }
    }

    /** Order is complete for a single scope, never an arbitrary cross-book subset. */
    suspend fun reorder(ids: List<Long>): Result<Unit> = colorRuleResult {
        dao.inTransaction {
            if (ids.isEmpty() || ids.size != ids.toSet().size || ids.size > ColorRuleTransfer.MAX_RULES) {
                throw ColorRuleException(ColorRuleError.INVALID_ORDER)
            }
            val all = dao.getAll()
            val scope = all.firstOrNull { it.id == ids.first() }?.bookUrl
                ?: throw ColorRuleException(ColorRuleError.NOT_FOUND)
            val rules = all.filter { it.bookUrl == scope }
            if (rules.map { it.id }.toSet() != ids.toSet()) {
                throw ColorRuleException(ColorRuleError.INVALID_ORDER)
            }
            val indexed = rules.associateBy { it.id }
            dao.updateAll(ids.mapIndexed { order, id ->
                indexed.getValue(id).copy(sortOrder = order)
            })
        }
    }

    suspend fun move(id: Long, direction: Int): Result<Unit> = colorRuleResult {
        if (direction != -1 && direction != 1) throw ColorRuleException(ColorRuleError.INVALID_ORDER)
        dao.inTransaction {
            val rule = find(id)
            val rules = dao.getAll().filter { it.bookUrl == rule.bookUrl }
                .sortedWith(compareBy<ReadColorRule> { it.sortOrder }.thenBy { it.id }).toMutableList()
            val from = rules.indexOfFirst { it.id == id }
            val to = from + direction
            if (to in rules.indices) {
                rules.add(to, rules.removeAt(from))
                dao.updateAll(rules.mapIndexed { order, item -> item.copy(sortOrder = order) })
            }
        }
    }

    suspend fun applyImport(
        rules: List<ReadColorRule>,
        bookUrl: String,
        overwriteColors: Boolean,
    ): Result<ColorRuleImportStats> = colorRuleResult {
        if (rules.size > ColorRuleTransfer.MAX_RULES) throw ColorRuleException(ColorRuleError.INPUT_TOO_LARGE)
        val valid = rules.map { ColorRuleTransfer.validate(it).getOrThrow() }
        var inserted = 0
        var updated = 0
        var skipped = 0
        dao.inTransaction {
            val existing = dao.getAll().toMutableList()
            for (incoming in valid) {
                if (incoming.bookUrl.isNotBlank() && incoming.bookUrl != bookUrl) {
                    skipped++
                    continue
                }
                val same = existing.firstOrNull { ColorRuleTransfer.sameIdentity(it, incoming) }
                if (same != null) {
                    if (!overwriteColors || ColorRuleTransfer.equivalentIgnoringId(same, incoming)) {
                        skipped++
                    } else {
                        val replacement = same.copy(
                            foregroundColor = incoming.foregroundColor,
                            backgroundColor = incoming.backgroundColor,
                            contentForegroundColor = incoming.contentForegroundColor,
                            contentBackgroundColor = incoming.contentBackgroundColor,
                        )
                        dao.update(replacement)
                        existing[existing.indexOf(same)] = replacement
                        updated++
                    }
                } else {
                    val newRule = incoming.copy(id = 0,
                        sortOrder = nextOrder(existing.filter { it.bookUrl == incoming.bookUrl }))
                    val id = dao.insert(newRule)
                    if (id <= 0) throw ColorRuleException(ColorRuleError.STORAGE)
                    existing += newRule.copy(id = id)
                    inserted++
                }
            }
        }
        ColorRuleImportStats(inserted, updated, skipped)
    }

    private suspend fun find(id: Long) = dao.getAll().firstOrNull { it.id == id }
        ?: throw ColorRuleException(ColorRuleError.NOT_FOUND)

    private fun nextOrder(rules: List<ReadColorRule>): Int {
        if (rules.size >= ColorRuleTransfer.MAX_RULES) throw ColorRuleException(ColorRuleError.INPUT_TOO_LARGE)
        val last = rules.maxOfOrNull { it.sortOrder } ?: -1
        if (last == Int.MAX_VALUE) throw ColorRuleException(ColorRuleError.INVALID_ORDER)
        return last + 1
    }
}
