package com.openyt.core.automation

import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionControllerTest {
    @Test
    fun onlyNewEpisodesQueueAndSeenIdsSurviveControllerRestart() = runTest {
        val repository = MemorySubscriptions()
        var episodes = listOf(FeedEpisode("old", "Old", "https://example.com/old.mp3"))
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), backgroundScope)
        fun controller() = SubscriptionController(repository, FeedReader { episodes }, FakeDownloadEngine(), coordinator,
            { "/tmp/subscriptions" }, { 10_000L }, { "rule-1" }, backgroundScope)
        val first = controller()
        assertTrue(first.add("Podcast", "https://example.com/feed", SubscriptionKind.FEED, 60, false))
        assertEquals(0, first.check("rule-1"))
        episodes = episodes + FeedEpisode("new", "New", "https://example.com/new.mp3")
        assertEquals(1, first.check("rule-1"))
        assertEquals(0, first.check("rule-1"))
        assertEquals(1, coordinator.tasks.value.size)
        val restored = controller()
        restored.restore()
        assertEquals(0, restored.check("rule-1"))
        assertEquals(1, coordinator.tasks.value.size)
        assertEquals("rule-1:new", coordinator.tasks.value.single().request.options.originKey)
    }

    @Test
    fun failedChecksWaitForTheirIntervalBeforeTryingAgain() = runTest {
        var now = 10_000L
        var calls = 0
        val repository = MemorySubscriptions()
        val controller = SubscriptionController(repository, FeedReader { calls++; error("network failure") }, FakeDownloadEngine(),
            DownloadCoordinator(FakeDownloadEngine(), backgroundScope), { "/tmp" }, { now }, { "rule" }, backgroundScope)
        controller.add("Feed", "https://example.com/feed", SubscriptionKind.FEED, 60, false)
        controller.checkDue()
        assertEquals(1, calls)
        assertTrue("rule" in controller.failedIds.value)
        now += 30_000
        controller.checkDue()
        assertEquals(1, calls)
        now += 3_600_000
        controller.checkDue()
        assertEquals(2, calls)
    }

    @Test
    fun invalidRulesNeverReachTheRepository() = runTest {
        val repository = MemorySubscriptions()
        val controller = SubscriptionController(repository, FeedReader { emptyList() }, FakeDownloadEngine(),
            DownloadCoordinator(FakeDownloadEngine(), backgroundScope), { "/tmp" }, { 1 }, { "id" }, backgroundScope)
        assertEquals(false, controller.add("Invalid", "file:///tmp/feed.xml", SubscriptionKind.FEED, 60, false))
        assertEquals(false, controller.add("Invalid", "https://example.com", SubscriptionKind.FEED, 0, false))
        assertTrue(repository.load().isEmpty())
    }

    private class MemorySubscriptions : SubscriptionRepository {
        private val values = mutableMapOf<String, SubscriptionRule>()
        override suspend fun load(): List<SubscriptionRule> = values.values.toList()
        override suspend fun save(rule: SubscriptionRule) { values[rule.id] = rule }
        override suspend fun delete(id: String) { values.remove(id) }
    }
}
