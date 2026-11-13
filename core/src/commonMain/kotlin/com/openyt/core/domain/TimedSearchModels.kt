package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
sealed interface TimedSearchScope {
    @Serializable
    data object Library : TimedSearchScope

    @Serializable
    data class Entry(val entryId: String) : TimedSearchScope

    @Serializable
    data class Collection(val collectionId: String) : TimedSearchScope
}

@Serializable
data class TimedSearchFilter(
    val language: String? = null,
    val trackId: String? = null,
)

@Serializable
data class TimedSearchPagination(
    val page: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(page >= 0) { "page must be non-negative: $page" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be between 1 and $MAX_PAGE_SIZE: $pageSize" }
    }

    val offset: Int get() = page * pageSize

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
    }
}

@Serializable
data class TextHighlightRange(
    val start: Int,
    val endInclusive: Int,
) {
    val length: Int get() = (endInclusive - start + 1).coerceAtLeast(0)
    val asIntRange: IntRange get() = start..endInclusive
}

@Serializable
data class TimedSearchExcerpt(
    val text: String,
    val highlightRanges: List<TextHighlightRange> = emptyList(),
)

@Serializable
data class TimedSearchMatch(
    val matchId: String,
    val entryId: String,
    val entryTitle: String?,
    val trackId: String,
    val cueId: String,
    val language: String?,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long = (endMs - startMs).coerceAtLeast(0L),
    val timebaseOffsetMs: Long = 0L,
    val matchedText: String,
    val displayText: String,
    val speaker: String? = null,
    val excerpt: TimedSearchExcerpt,
)

@Serializable
enum class TimedSearchIndexStatus {
    READY,
    PARTIALLY_INDEXED,
}

@Serializable
sealed interface TimedSearchResult {
    @Serializable
    data class Success(
        val items: List<TimedSearchMatch>,
        val totalMatches: Int,
        val page: Int,
        val pageSize: Int,
        val hasMore: Boolean,
        val indexStatus: TimedSearchIndexStatus,
        val normalizedQuery: String,
    ) : TimedSearchResult

    @Serializable
    data class IndexUnavailable(
        val reason: IndexUnavailableReason,
        val message: String,
    ) : TimedSearchResult

    @Serializable
    data class InvalidQuery(
        val reason: InvalidQueryReason,
        val message: String,
    ) : TimedSearchResult
}

@Serializable
enum class IndexUnavailableReason {
    NO_SUBTITLE_TRACKS,
    INDEX_PENDING_OR_STALE,
    INDEX_FAILED,
    ENTRY_NOT_FOUND,
    COLLECTION_EMPTY,
}

@Serializable
enum class InvalidQueryReason {
    EMPTY,
    EXCEEDS_LENGTH_LIMIT,
    EXCEEDS_TOKEN_LIMIT,
}
