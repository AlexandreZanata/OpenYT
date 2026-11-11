package com.openyt.data

import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackQueue
import com.openyt.core.domain.MediaKind
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class PlaybackStoreTest {
    @Test
    fun queueAndPositionSurviveDatabaseReopen() = runBlocking {
        val file = Files.createTempFile("openyt-playback-", ".db").toFile()
        try {
            val store = SqlDelightPlaybackStore(createOpenYtDatabase(file.absolutePath))
            val queue = PlaybackQueue.of(listOf(PlayableMedia("/media/ação.mkv", "Ação", MediaKind.VIDEO)), 0)
            store.saveQueue(queue)
            store.savePosition("/media/ação.mkv", PlaybackCheckpoint(12_000, 90_000))
            val reopened = SqlDelightPlaybackStore(createOpenYtDatabase(file.absolutePath))
            assertEquals(queue, reopened.loadQueue())
            assertEquals(12_000, reopened.position("/media/ação.mkv")?.resumeMillis)
        } finally {
            file.delete()
        }
    }
}
