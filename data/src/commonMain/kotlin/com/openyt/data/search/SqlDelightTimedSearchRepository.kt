package com.openyt.data.search

import com.openyt.core.application.TimedSearchQueryValidator
import com.openyt.core.application.TimedSearchRepository
import com.openyt.core.application.TimedSearchValidationResult
import com.openyt.core.domain.IndexUnavailableReason
import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchIndexStatus
import com.openyt.core.domain.TimedSearchMatch
import com.openyt.core.domain.TimedSearchPagination
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class SqlDelightTimedSearchRepository(
    private val database: OpenYtDatabase,
) : TimedSearchRepository {

    override suspend fun search(
        query: String,
        scope: TimedSearchScope,
        filter: TimedSearchFilter,
        pagination: TimedSearchPagination,
    ): TimedSearchResult = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()

        val validation = TimedSearchQueryValidator.validate(query)
        if (validation is TimedSearchValidationResult.Invalid) {
            return@withContext TimedSearchResult.InvalidQuery(
                reason = validation.reason,
                message = validation.message,
            )
        }
        val validated = (validation as TimedSearchValidationResult.Valid).query

        try {
            val indexStatus = resolveScopeAndIndexStatus(scope)
            if (indexStatus is ScopeStatusResult.Unavailable) {
                return@withContext TimedSearchResult.IndexUnavailable(
                    reason = indexStatus.reason,
                    message = indexStatus.message,
                )
            }
            val status = (indexStatus as ScopeStatusResult.Available).status

            currentCoroutineContext().ensureActive()

            val totalMatches = countMatches(scope, validated.ftsMatchQuery, filter)
            if (totalMatches == 0) {
                return@withContext TimedSearchResult.Success(
                    items = emptyList(),
                    totalMatches = 0,
                    page = pagination.page,
                    pageSize = pagination.pageSize,
                    hasMore = false,
                    indexStatus = status,
                    normalizedQuery = validated.normalizedQuery,
                )
            }

            currentCoroutineContext().ensureActive()

            val items = fetchMatches(scope, validated.ftsMatchQuery, filter, pagination, validated.tokens)
            val hasMore = (pagination.offset + items.size) < totalMatches

            currentCoroutineContext().ensureActive()

            TimedSearchResult.Success(
                items = items,
                totalMatches = totalMatches,
                page = pagination.page,
                pageSize = pagination.pageSize,
                hasMore = hasMore,
                indexStatus = status,
                normalizedQuery = validated.normalizedQuery,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            TimedSearchResult.IndexUnavailable(
                reason = IndexUnavailableReason.INDEX_FAILED,
                message = "Search query execution failed: ${e.message ?: "unknown error"}",
            )
        }
    }

    private fun resolveScopeAndIndexStatus(scope: TimedSearchScope): ScopeStatusResult {
        return when (scope) {
            is TimedSearchScope.Entry -> {
                val entry = database.downloadQueries.selectLibraryEntryById(scope.entryId).executeAsOneOrNull()
                if (entry == null) {
                    return ScopeStatusResult.Unavailable(
                        reason = IndexUnavailableReason.ENTRY_NOT_FOUND,
                        message = "Entry '${scope.entryId}' not found in library",
                    )
                }
                val counts = database.downloadQueries.countIndexesForEntry(scope.entryId).executeAsOne()
                val total = counts.total
                val ready = counts.ready ?: 0L
                val failed = counts.failed ?: 0L
                if (total == 0L) {
                    return ScopeStatusResult.Unavailable(
                        reason = IndexUnavailableReason.NO_SUBTITLE_TRACKS,
                        message = "No subtitle tracks found for entry '${scope.entryId}'",
                    )
                }
                if (ready == 0L) {
                    return if (failed > 0L) {
                        ScopeStatusResult.Unavailable(
                            reason = IndexUnavailableReason.INDEX_FAILED,
                            message = "Subtitle indexing failed for entry '${scope.entryId}'",
                        )
                    } else {
                        ScopeStatusResult.Unavailable(
                            reason = IndexUnavailableReason.INDEX_PENDING_OR_STALE,
                            message = "Subtitle index is not ready for entry '${scope.entryId}'",
                        )
                    }
                }
                val status = if (ready < total) {
                    TimedSearchIndexStatus.PARTIALLY_INDEXED
                } else {
                    TimedSearchIndexStatus.READY
                }
                ScopeStatusResult.Available(status)
            }

            is TimedSearchScope.Collection -> {
                val entryCount = database.downloadQueries.countEntriesInCollection(scope.collectionId).executeAsOne()
                if (entryCount == 0L) {
                    return ScopeStatusResult.Unavailable(
                        reason = IndexUnavailableReason.COLLECTION_EMPTY,
                        message = "Collection '${scope.collectionId}' has no entries",
                    )
                }
                val counts = database.downloadQueries.countIndexesInCollection(scope.collectionId).executeAsOne()
                val total = counts.total
                val ready = counts.ready ?: 0L
                val failed = counts.failed ?: 0L
                if (total == 0L) {
                    return ScopeStatusResult.Unavailable(
                        reason = IndexUnavailableReason.NO_SUBTITLE_TRACKS,
                        message = "No subtitle tracks found in collection '${scope.collectionId}'",
                    )
                }
                if (ready == 0L) {
                    return if (failed > 0L) {
                        ScopeStatusResult.Unavailable(
                            reason = IndexUnavailableReason.INDEX_FAILED,
                            message = "Subtitle indexing failed in collection '${scope.collectionId}'",
                        )
                    } else {
                        ScopeStatusResult.Unavailable(
                            reason = IndexUnavailableReason.INDEX_PENDING_OR_STALE,
                            message = "Subtitle index is not ready in collection '${scope.collectionId}'",
                        )
                    }
                }
                val status = if (ready < total) {
                    TimedSearchIndexStatus.PARTIALLY_INDEXED
                } else {
                    TimedSearchIndexStatus.READY
                }
                ScopeStatusResult.Available(status)
            }

            is TimedSearchScope.Library -> {
                val libraryCount = database.downloadQueries.countLibraryEntries().executeAsOne()
                if (libraryCount == 0L) {
                    return ScopeStatusResult.Unavailable(
                        reason = IndexUnavailableReason.NO_SUBTITLE_TRACKS,
                        message = "Library has no media entries",
                    )
                }
                val counts = database.downloadQueries.countIndexesInLibrary().executeAsOne()
                val total = counts.total
                val ready = counts.ready ?: 0L
                val failed = counts.failed ?: 0L
                if (total == 0L) {
                    return ScopeStatusResult.Unavailable(
                        reason = IndexUnavailableReason.NO_SUBTITLE_TRACKS,
                        message = "No subtitle tracks found in library",
                    )
                }
                if (ready == 0L) {
                    return if (failed > 0L) {
                        ScopeStatusResult.Unavailable(
                            reason = IndexUnavailableReason.INDEX_FAILED,
                            message = "Subtitle indexing failed in library",
                        )
                    } else {
                        ScopeStatusResult.Unavailable(
                            reason = IndexUnavailableReason.INDEX_PENDING_OR_STALE,
                            message = "Subtitle index is not ready in library",
                        )
                    }
                }
                val status = if (ready < total) {
                    TimedSearchIndexStatus.PARTIALLY_INDEXED
                } else {
                    TimedSearchIndexStatus.READY
                }
                ScopeStatusResult.Available(status)
            }
        }
    }

    private fun countMatches(
        scope: TimedSearchScope,
        ftsMatchQuery: String,
        filter: TimedSearchFilter,
    ): Int {
        return when (scope) {
            is TimedSearchScope.Library -> database.downloadQueries.countSubtitleCuesLibrary(
                match_query = ftsMatchQuery,
                language = filter.language,
                track_id = filter.trackId,
            ).executeAsOne().toInt()

            is TimedSearchScope.Entry -> database.downloadQueries.countSubtitleCuesEntry(
                match_query = ftsMatchQuery,
                entry_id = scope.entryId,
                language = filter.language,
                track_id = filter.trackId,
            ).executeAsOne().toInt()

            is TimedSearchScope.Collection -> database.downloadQueries.countSubtitleCuesCollection(
                match_query = ftsMatchQuery,
                collection_id = scope.collectionId,
                language = filter.language,
                track_id = filter.trackId,
            ).executeAsOne().toInt()
        }
    }

    private fun fetchMatches(
        scope: TimedSearchScope,
        ftsMatchQuery: String,
        filter: TimedSearchFilter,
        pagination: TimedSearchPagination,
        tokens: List<String>,
    ): List<TimedSearchMatch> {
        return when (scope) {
            is TimedSearchScope.Library -> database.downloadQueries.searchSubtitleCuesLibrary(
                match_query = ftsMatchQuery,
                language = filter.language,
                track_id = filter.trackId,
                limit = pagination.pageSize.toLong(),
                offset = pagination.offset.toLong(),
            ).executeAsList().map { row ->
                val excerpt = TimedSearchQueryValidator.extractExcerpt(
                    plainText = row.plain_text,
                    searchTerms = tokens,
                )
                TimedSearchMatch(
                    matchId = "${row.track_id}:${row.cue_id}",
                    entryId = row.entry_id,
                    entryTitle = row.entry_title,
                    trackId = row.track_id,
                    cueId = row.cue_id,
                    language = row.language,
                    startMs = row.start_ms,
                    endMs = row.end_ms,
                    durationMs = (row.end_ms - row.start_ms).coerceAtLeast(0L),
                    timebaseOffsetMs = row.timebase_offset_ms,
                    matchedText = row.plain_text,
                    displayText = row.display_text,
                    speaker = row.speaker,
                    excerpt = excerpt,
                )
            }

            is TimedSearchScope.Entry -> database.downloadQueries.searchSubtitleCuesEntry(
                match_query = ftsMatchQuery,
                entry_id = scope.entryId,
                language = filter.language,
                track_id = filter.trackId,
                limit = pagination.pageSize.toLong(),
                offset = pagination.offset.toLong(),
            ).executeAsList().map { row ->
                val excerpt = TimedSearchQueryValidator.extractExcerpt(
                    plainText = row.plain_text,
                    searchTerms = tokens,
                )
                TimedSearchMatch(
                    matchId = "${row.track_id}:${row.cue_id}",
                    entryId = row.entry_id,
                    entryTitle = row.entry_title,
                    trackId = row.track_id,
                    cueId = row.cue_id,
                    language = row.language,
                    startMs = row.start_ms,
                    endMs = row.end_ms,
                    durationMs = (row.end_ms - row.start_ms).coerceAtLeast(0L),
                    timebaseOffsetMs = row.timebase_offset_ms,
                    matchedText = row.plain_text,
                    displayText = row.display_text,
                    speaker = row.speaker,
                    excerpt = excerpt,
                )
            }

            is TimedSearchScope.Collection -> database.downloadQueries.searchSubtitleCuesCollection(
                match_query = ftsMatchQuery,
                collection_id = scope.collectionId,
                language = filter.language,
                track_id = filter.trackId,
                limit = pagination.pageSize.toLong(),
                offset = pagination.offset.toLong(),
            ).executeAsList().map { row ->
                val excerpt = TimedSearchQueryValidator.extractExcerpt(
                    plainText = row.plain_text,
                    searchTerms = tokens,
                )
                TimedSearchMatch(
                    matchId = "${row.track_id}:${row.cue_id}",
                    entryId = row.entry_id,
                    entryTitle = row.entry_title,
                    trackId = row.track_id,
                    cueId = row.cue_id,
                    language = row.language,
                    startMs = row.start_ms,
                    endMs = row.end_ms,
                    durationMs = (row.end_ms - row.start_ms).coerceAtLeast(0L),
                    timebaseOffsetMs = row.timebase_offset_ms,
                    matchedText = row.plain_text,
                    displayText = row.display_text,
                    speaker = row.speaker,
                    excerpt = excerpt,
                )
            }
        }
    }

    private sealed interface ScopeStatusResult {
        data class Available(val status: TimedSearchIndexStatus) : ScopeStatusResult
        data class Unavailable(val reason: IndexUnavailableReason, val message: String) : ScopeStatusResult
    }
}
