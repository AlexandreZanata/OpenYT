package com.openyt.core.application

import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchPagination
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class TimedSearchUseCase(
    private val repository: TimedSearchRepository,
) {
    suspend fun execute(
        query: String,
        scope: TimedSearchScope = TimedSearchScope.Library,
        filter: TimedSearchFilter = TimedSearchFilter(),
        pagination: TimedSearchPagination = TimedSearchPagination(),
    ): TimedSearchResult {
        currentCoroutineContext().ensureActive()

        val validation = TimedSearchQueryValidator.validate(query)
        if (validation is TimedSearchValidationResult.Invalid) {
            return TimedSearchResult.InvalidQuery(
                reason = validation.reason,
                message = validation.message,
            )
        }

        currentCoroutineContext().ensureActive()
        return repository.search(query, scope, filter, pagination)
    }
}
