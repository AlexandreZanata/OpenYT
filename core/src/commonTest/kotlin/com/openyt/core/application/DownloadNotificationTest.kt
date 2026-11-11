package com.openyt.core.application

import com.openyt.core.domain.*
import com.openyt.core.testing.FakeDownloadEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadNotificationTest {
    @Test fun restoredHistoryIsSilentAndFastNewDownloadsNotifyOnce() {
        val tracker = DownloadNotificationTracker()
        fun task(id: String, status: DownloadStatus) = DownloadTask(id, FakeDownloadEngine.SAMPLE_MEDIA, DownloadRequest("https://example.com/v", destinationDirectory = "/fixture"), status)
        val restored = task("old", DownloadStatus.COMPLETED)
        assertTrue(tracker.update(listOf(restored)).isEmpty())
        val new = task("new", DownloadStatus.COMPLETED)
        assertEquals(listOf(new), tracker.update(listOf(restored, new)))
        assertTrue(tracker.update(listOf(restored, new)).isEmpty())
        assertTrue(tracker.update(listOf(restored, new, task("cancel", DownloadStatus.CANCELED))).isEmpty())
    }
}
