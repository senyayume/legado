package io.legado.app.model.read

import io.legado.app.data.dao.BookHighlightDao
import io.legado.app.data.entities.BookHighlight
import kotlinx.coroutines.CancellationException

enum class ReaderHighlightError { INVALID_SELECTION, STORAGE, REFRESH, STALE_SELECTION }

class ReaderHighlightException(
    val reason: ReaderHighlightError,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

/** 唯一写入边界：校验原文区间，复用数据库唯一索引去重。 */
class BookHighlightRepository(private val dao: BookHighlightDao) {
    suspend fun save(highlight: BookHighlight): Result<BookHighlight> {
        if (highlight.bookUrl.isBlank() || highlight.chapterIndex < 0 ||
            highlight.chapterPos < 0 || highlight.chapterPosEnd <= highlight.chapterPos ||
            highlight.bookText.isBlank() ||
            highlight.bookText.length > BookHighlightMatcher.MAX_HIGHLIGHT_TEXT_LENGTH ||
            highlight.chapterPosEnd.toLong() - highlight.chapterPos != highlight.bookText.length.toLong()
        ) return Result.failure(ReaderHighlightException(ReaderHighlightError.INVALID_SELECTION))
        return highlightResult(ReaderHighlightError.STORAGE) {
            exact(highlight)?.let { return@highlightResult it }
            repeat(4) { attempt ->
                val candidate = highlight.copy(time = highlight.time + attempt)
                if (dao.insert(candidate) != -1L) return@highlightResult candidate
                exact(highlight)?.let { return@highlightResult it }
            }
            throw ReaderHighlightException(ReaderHighlightError.STORAGE)
        }
    }

    suspend fun delete(highlight: BookHighlight): Result<Unit> =
        highlightResult(ReaderHighlightError.STORAGE) {
            val existing = exact(highlight)
            if (existing?.time == highlight.time) dao.delete(existing)
        }

    private suspend fun exact(value: BookHighlight) = dao.findExact(
        value.bookUrl, value.chapterIndex, value.chapterPos, value.chapterPosEnd,
    )
}

/** 清除选区只能在写入和章节投影均成功之后发生。 */
class ReaderHighlightCommands(private val repository: BookHighlightRepository) {
    suspend fun save(
        highlight: BookHighlight,
        refresh: suspend () -> Unit,
        onSuccess: () -> Unit,
    ): Result<Unit> {
        repository.save(highlight).exceptionOrNull()?.let { return Result.failure(it) }
        return highlightResult(ReaderHighlightError.REFRESH) {
            refresh()
            onSuccess()
        }
    }

    suspend fun delete(
        highlight: BookHighlight,
        refresh: suspend () -> Unit,
    ): Result<Unit> {
        repository.delete(highlight).exceptionOrNull()?.let { return Result.failure(it) }
        return highlightResult(ReaderHighlightError.REFRESH) { refresh() }
    }
}

private suspend fun <T> highlightResult(
    reason: ReaderHighlightError,
    block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(if (error is ReaderHighlightException) error else ReaderHighlightException(reason, error))
}
