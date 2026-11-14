package com.openyt.data

import com.openyt.core.automation.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionRulesPersistenceTest {
    @Test fun ruleCandidatesReasonsAndRevisionSurviveRealDatabaseReopen() = runBlocking {
        val root = Files.createTempDirectory("subscription-persistence-").toFile()
        try {
            val path = root.resolve("library.db").absolutePath
            val rule = SubscriptionRule("rule", "Study", "https://example.com/feed", SubscriptionKind.FEED,
                initialized = true, seenIds = setOf("old"), revision = 3,
                filters = SubscriptionFilters(minimumDurationSeconds = 20, profileId = "builtin-study", wifiOnly = true),
                candidates = listOf(SubscriptionCandidate(FeedEpisode("next", "Next", "https://example.com/next"), CandidateState.DEFERRED, SubscriptionReason.WIFI_REQUIRED, 3)))
            SqlDelightSubscriptions(createOpenYtDatabase(path)).save(rule)
            assertEquals(rule, SqlDelightSubscriptions(createOpenYtDatabase(path)).load().single())
        } finally { root.deleteRecursively() }
    }
}
