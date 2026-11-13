package com.openyt.core.application

import com.openyt.core.domain.IndexUnavailableReason
import com.openyt.core.domain.InvalidQueryReason
import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchIndexStatus
import com.openyt.core.domain.TimedSearchPagination
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimedSearchUseCaseTest {

    private class FakeTimedSearchRepository : TimedSearchRepository {
        var callCount = 0
        var lastQuery: String? = null
        var lastScope: TimedSearchScope? = null
        var lastFilter: TimedSearchFilter? = null
        var lastPagination: TimedSearchPagination? = null

        var resultToReturn: TimedSearchResult = TimedSearchResult.Success(
            items = emptyList(),
            totalMatches = 0,
            page = 0,
            pageSize = 20,
            hasMore = false,
            indexStatus = TimedSearchIndexStatus.READY,
            normalizedQuery = "",
        )

        override suspend fun search(
            query: String,
            scope: TimedSearchScope,
            filter: TimedSearchFilter,
            pagination: TimedSearchPagination,
        ): TimedSearchResult {
            callCount++
            lastQuery = query
            lastScope = scope
            lastFilter = filter
            lastPagination = pagination
            return resultToReturn
        }
    }

    @Test
    fun rejectsInvalidQueriesBeforeCallingRepository() = runBlocking {
        val fakeRepo = FakeTimedSearchRepository()
        val useCase = TimedSearchUseCase(fakeRepo)

        val emptyResult = useCase.execute("   ")
        assertIs<TimedSearchResult.InvalidQuery>(emptyResult)
        assertEquals(InvalidQueryReason.EMPTY, emptyResult.reason)
        assertEquals(0, fakeRepo.callCount)

        val tooLong = useCase.execute("a".repeat(257))
        assertIs<TimedSearchResult.InvalidQuery>(tooLong)
        assertEquals(InvalidQueryReason.EXCEEDS_LENGTH_LIMIT, tooLong.reason)
        assertEquals(0, fakeRepo.callCount)

        val tooManyTokens = useCase.execute("1 2 3 4 5 6 7 8 9")
        assertIs<TimedSearchResult.InvalidQuery>(tooManyTokens)
        assertEquals(InvalidQueryReason.EXCEEDS_TOKEN_LIMIT, tooManyTokens.reason)
        assertEquals(0, fakeRepo.callCount)
    }

    @Test
    fun delegatesValidQueriesToRepository() = runBlocking {
        val fakeRepo = FakeTimedSearchRepository()
        val useCase = TimedSearchUseCase(fakeRepo)

        val query = "aprendizado kotlin"
        val scope = TimedSearchScope.Collection("col-42")
        val filter = TimedSearchFilter(language = "pt")
        val pagination = TimedSearchPagination(page = 1, pageSize = 15)

        val result = useCase.execute(query, scope, filter, pagination)
        assertIs<TimedSearchResult.Success>(result)
        assertEquals(1, fakeRepo.callCount)
        assertEquals(query, fakeRepo.lastQuery)
        assertEquals(scope, fakeRepo.lastScope)
        assertEquals(filter, fakeRepo.lastFilter)
        assertEquals(pagination, fakeRepo.lastPagination)
    }

    @Test
    fun honorsCooperativeCancellation() = runBlocking {
        val fakeRepo = FakeTimedSearchRepository()
        val useCase = TimedSearchUseCase(fakeRepo)

        val job = async {
            useCase.execute("termo de busca")
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
