package com.openyt.core.application

import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchPagination
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope

interface TimedSearchRepository {
    suspend fun search(
        query: String,
        scope: TimedSearchScope = TimedSearchScope.Library,
        filter: TimedSearchFilter = TimedSearchFilter(),
        pagination: TimedSearchPagination = TimedSearchPagination(),
    ): TimedSearchResult
}
