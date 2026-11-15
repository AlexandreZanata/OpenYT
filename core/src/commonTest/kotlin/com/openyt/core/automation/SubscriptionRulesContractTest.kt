package com.openyt.core.automation

import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.MemoryDownloadProfileRepository
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionRulesContractTest {
    private class Repository : SubscriptionRepository {
        val data = mutableMapOf<String, String>()
        override suspend fun load() = data.values.map { Json.decodeFromString<SubscriptionRule>(it) }
        override suspend fun save(rule: SubscriptionRule) { data[rule.id] = Json.encodeToString(rule) }
        override suspend fun delete(id: String) { data.remove(id) }
    }

    @Test fun legacyRulesDecodeAndUnknownMetadataNeverPassesRequiredFilters() {
        val old = Json.decodeFromString<SubscriptionRule>("""{"id":"r","name":"Old","url":"https://example.com/feed","kind":"FEED","initialized":true,"seenIds":["old"]}""")
        assertEquals(SubscriptionFilters(), old.filters)
        assertTrue(old.candidates.isEmpty())
        val state = SubscriptionDeviceState(true, availableBytes = 100)
        val episode = FeedEpisode("one", "One", "https://example.com/one")
        assertEquals(SubscriptionReason.UNKNOWN_DURATION, SubscriptionPolicy.evaluate(SubscriptionFilters(minimumDurationSeconds = 10), episode, state))
        assertEquals(SubscriptionReason.UNKNOWN_SIZE, SubscriptionPolicy.evaluate(SubscriptionFilters(minimumFreeBytes = 10), episode, state))
        assertEquals(SubscriptionReason.LOW_SPACE, SubscriptionPolicy.evaluate(SubscriptionFilters(minimumFreeBytes = 10), episode.copy(sizeBytes = Long.MAX_VALUE), state))
        assertFalse(SubscriptionFilters(minimumDurationSeconds = 20, maximumDurationSeconds = 10).isValid())
    }

    @Test fun meteredWifiAndUnmeteredEthernetAreDifferentAndUnknownIsNotTrue() {
        assertEquals(SubscriptionReason.UNMETERED_REQUIRED, SubscriptionPolicy.networkReason(SubscriptionFilters(unmeteredOnly = true), SubscriptionDeviceState(true, wifi = true, unmetered = false)))
        assertEquals(SubscriptionReason.WIFI_REQUIRED, SubscriptionPolicy.networkReason(SubscriptionFilters(wifiOnly = true), SubscriptionDeviceState(true, wifi = false, unmetered = true)))
        assertEquals(SubscriptionReason.CAPABILITY_UNKNOWN, SubscriptionPolicy.networkReason(SubscriptionFilters(chargingOnly = true), SubscriptionDeviceState(true)))
    }

    @Test fun cappedEpisodesPersistAndAllEventuallyQueueExactlyOnceAcrossRestart() = runTest {
        val repository = Repository()
        val engine = FakeDownloadEngine()
        val coordinator = DownloadCoordinator(engine, backgroundScope)
        val episodes = (1..5).map { FeedEpisode("$it", "Episode $it", "https://example.com/$it.mp3") }
        fun controller() = SubscriptionController(repository, FeedReader { episodes }, engine, coordinator,
            { "/tmp/subscription" }, { 10_000 }, { "rule" }, backgroundScope)
        val first = controller()
        assertTrue(first.add("Feed", "https://example.com/feed", SubscriptionKind.FEED, 60, true, SubscriptionFilters(maxNewPerCheck = 2)))
        assertEquals(2, first.check("rule"))
        assertEquals(3, first.rules.value.single().candidates.count { it.state == CandidateState.DEFERRED })
        assertEquals(setOf("1", "2"), first.rules.value.single().seenIds)
        val second = controller()
        second.restore()
        assertEquals(2, second.check("rule"))
        assertEquals(1, second.check("rule"))
        assertEquals(0, second.check("rule"))
        assertEquals(5, coordinator.tasks.value.map { it.request.options.originKey }.toSet().size)
        assertEquals(5, second.rules.value.single().seenIds.size)
    }

    @Test fun previewDoesNotPersistOrEnqueueAndConstraintStopsAllNetworkRequests() = runTest {
        var requests = 0
        val repository = Repository()
        val engine = FakeDownloadEngine()
        val coordinator = DownloadCoordinator(engine, backgroundScope)
        var state = SubscriptionDeviceState(true)
        val controller = SubscriptionController(repository, FeedReader { requests++; listOf(FeedEpisode("1", "One", "https://example.com/1.mp3")) }, engine, coordinator,
            { "/tmp" }, { 1 }, { "rule" }, backgroundScope, environment = SubscriptionEnvironment { state })
        controller.add("Feed", "https://example.com/feed", SubscriptionKind.FEED, 60, true)
        val before = repository.data.toMap()
        controller.preview("rule")
        assertEquals(before, repository.data)
        assertTrue(coordinator.tasks.value.isEmpty())
        assertEquals(SubscriptionReason.READY, controller.previews.value["rule"]!!.single().reason)
        state = SubscriptionDeviceState(false)
        controller.check("rule")
        assertEquals(1, requests)
        assertEquals(0, engine.inspectCalls)
        assertEquals(SubscriptionReason.OFFLINE, controller.waitingReasons.value["rule"])
        assertTrue(coordinator.tasks.value.isEmpty())
    }

    @Test fun filterEditsRequireExplicitReplayAndProfileSnapshotSurvivesEditing() = runTest {
        val repository = Repository()
        val engine = FakeDownloadEngine()
        val coordinator = DownloadCoordinator(engine, backgroundScope)
        val profiles = MemoryDownloadProfileRepository()
        val custom = DownloadProfile.study().copy(id = "custom", name = "Custom", isBuiltIn = false)
        profiles.saveCustomProfile(custom)
        val episode = FeedEpisode("one", "One", "https://example.com/one", durationSeconds = 42, sizeBytes = 1024)
        val controller = SubscriptionController(repository, FeedReader { listOf(episode) }, engine, coordinator,
            { "/tmp" }, { 1 }, { "rule" }, backgroundScope, profileRepository = profiles)
        controller.add("Feed", "https://example.com/feed", SubscriptionKind.FEED, 60, true, SubscriptionFilters(minimumDurationSeconds = 60))
        assertEquals(0, controller.check("rule"))
        assertEquals(CandidateState.FILTERED, controller.rules.value.single().candidates.single().state)
        controller.updateFilters("rule", SubscriptionFilters(profileId = custom.id))
        assertEquals(0, controller.check("rule"))
        controller.retryCandidate("rule", "one")
        assertEquals(1, controller.check("rule"))
        val snapshot = coordinator.tasks.value.single().request
        profiles.saveCustomProfile(custom.copy(name = "Changed", maxHeight = 480))
        assertEquals("Custom", snapshot.options.profileProvenance?.profileName)
        assertEquals("rule:one", snapshot.options.originKey)
        assertEquals(snapshot, coordinator.tasks.value.single().request)
    }

    @Test fun unknownDurationDefersInsteadOfMarkingSeenAndRecoversLater() = runTest {
        val repository = Repository()
        val engine = FakeDownloadEngine(inspection = EngineResult.Success(Inspection.Single(FakeDownloadEngine.SAMPLE_MEDIA.copy(durationSeconds = null))))
        val coordinator = DownloadCoordinator(engine, backgroundScope)
        val controller = SubscriptionController(repository, FeedReader { listOf(FeedEpisode("one", "One", "https://example.com/one")) }, engine, coordinator,
            { "/tmp" }, { 1 }, { "rule" }, backgroundScope)
        controller.add("Feed", "https://example.com/feed", SubscriptionKind.FEED, 60, true, SubscriptionFilters(minimumDurationSeconds = 10))
        assertEquals(0, controller.check("rule"))
        assertTrue(controller.rules.value.single().seenIds.isEmpty())
        assertEquals(SubscriptionReason.UNKNOWN_DURATION, controller.rules.value.single().candidates.single().reason)
        engine.inspection = EngineResult.Success(Inspection.Single(FakeDownloadEngine.SAMPLE_MEDIA))
        assertEquals(1, controller.check("rule"))
        assertEquals(1, coordinator.tasks.value.size)
    }

    @Test fun pendingLimitAndSpaceReservationsPreserveDeferredEpisodes() = runTest {
        val repository = Repository()
        val engine = FakeDownloadEngine()
        val coordinator = DownloadCoordinator(engine, backgroundScope)
        val controller = SubscriptionController(repository, FeedReader { (1..3).map { FeedEpisode("$it", "Episode $it", "https://example.com/$it", sizeBytes = 60) } }, engine, coordinator,
            { "/tmp" }, { 1 }, { "rule" }, backgroundScope, environment = SubscriptionEnvironment { SubscriptionDeviceState(true, availableBytes = 100) })
        controller.add("Feed", "https://example.com/feed", SubscriptionKind.FEED, 60, true, SubscriptionFilters(minimumFreeBytes = 20, maxPending = 1))
        assertEquals(1, controller.check("rule"))
        assertEquals(2, controller.rules.value.single().candidates.count { it.state == CandidateState.DEFERRED })
        assertEquals(60, coordinator.tasks.value.single().request.options.subscriptionReservedBytes)
        assertEquals(setOf("1"), controller.rules.value.single().seenIds)
    }

    @Test fun inspectionFailuresRotateWithoutStarvingLaterEpisodesBeyondTheFirstBatch() = runTest {
        val repository = Repository()
        val fake = FakeDownloadEngine()
        val engine = object : com.openyt.core.domain.DownloadEngine by fake {
            override suspend fun inspect(url: String): EngineResult<Inspection> = if (url.substringAfterLast('/').toInt() <= 220)
                EngineResult.Failure(com.openyt.core.domain.DownloadFailure.Network) else EngineResult.Success(Inspection.Single(FakeDownloadEngine.SAMPLE_MEDIA))
        }
        val coordinator = DownloadCoordinator(engine, backgroundScope)
        val controller = SubscriptionController(repository, FeedReader { (1..250).map { FeedEpisode("$it", "Episode $it", "https://example.com/$it") } }, engine, coordinator,
            { "/tmp" }, { 1 }, { "rule" }, backgroundScope)
        controller.add("Feed", "https://example.com/feed", SubscriptionKind.FEED, 60, true, SubscriptionFilters(minimumDurationSeconds = 10, maxNewPerCheck = 100, maxPending = 1000))
        repeat(15) { controller.check("rule") }
        assertEquals((221..250).map { "rule:$it" }.toSet(), coordinator.tasks.value.map { it.request.options.originKey }.toSet())
    }

    @Test fun filteredHistoryDoesNotFillDiscoveryForeverAndReplayRequiresExplicitApproval() = runTest {
        val repository = Repository()
        val episodes = (1..5000).map { FeedEpisode("$it", "Old $it", "https://example.com/$it", durationSeconds = 10) }
        repository.save(SubscriptionRule("rule", "Feed", "https://example.com/feed", SubscriptionKind.FEED, initialized = true,
            filters = SubscriptionFilters(minimumDurationSeconds = 100), candidates = episodes.map { SubscriptionCandidate(it, CandidateState.FILTERED, SubscriptionReason.DURATION_FILTER) }))
        val engine = FakeDownloadEngine()
        val coordinator = DownloadCoordinator(engine, backgroundScope)
        val controller = SubscriptionController(repository, FeedReader { episodes + FeedEpisode("new", "New", "https://example.com/new", durationSeconds = 200) }, engine, coordinator,
            { "/tmp" }, { 1 }, { "unused" }, backgroundScope)
        controller.restore()
        assertEquals(1, controller.check("rule"))
        assertEquals(4500, controller.rules.value.single().archivedFilteredIds.size)
        controller.updateFilters("rule", SubscriptionFilters())
        val before = repository.data.toMap()
        controller.previewHistory("rule")
        assertEquals(before, repository.data, "Historical preview cannot authorize future downloads")
        assertEquals(0, controller.check("rule"))
        controller.retryCandidate("rule", "1")
        assertEquals(1, controller.check("rule"))
        assertEquals(setOf("rule:new", "rule:1"), coordinator.tasks.value.map { it.request.options.originKey }.toSet())
    }
}
