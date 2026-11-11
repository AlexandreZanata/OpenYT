package com.openyt.core.application

import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.MediaInfo
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationConcurrencyTest {
    @Test fun simultaneousWatcherChecksCannotDuplicateTheSameEpisode() = runTest {
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), backgroundScope)
        val media = MediaInfo("episode", "generic", "Podcast", webpageUrl = "https://example.com/episode.mp3")
        val request = DownloadRequest(media.webpageUrl, destinationDirectory = "/fixture", options = DownloadOptions(originKey = "feed:episode"))
        List(100) { launch(Dispatchers.Default) { coordinator.enqueue(media, request) } }.joinAll()
        assertEquals(1, coordinator.tasks.value.size)
        assertEquals(1, coordinator.tasks.value.map { it.id }.distinct().size)
    }
}
