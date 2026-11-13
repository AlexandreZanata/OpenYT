package com.openyt.data

import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackQueue
import com.openyt.core.domain.MediaKind
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaybackStoreTest {

    @Test
    fun queueAndPositionSurviveDatabaseReopen() = runBlocking {
        val file = Files.createTempFile("openyt-playback-", ".db").toFile()
        try {
            val store = SqlDelightPlaybackStore(createOpenYtDatabase(file.absolutePath))
            val queue = PlaybackQueue.of(
                listOf(PlayableMedia("/media/ação.mkv", "Ação", MediaKind.VIDEO, entryId = "entry-123")),
                0,
            )
            store.saveQueue(queue)
            store.savePosition("/media/ação.mkv", PlaybackCheckpoint(12_000, 90_000, "entry-123"), "entry-123")
            val reopened = SqlDelightPlaybackStore(createOpenYtDatabase(file.absolutePath))
            assertEquals(queue, reopened.loadQueue())
            val checkpoint = reopened.position("/media/ação.mkv", "entry-123")
            assertNotNull(checkpoint)
            assertEquals(12_000, checkpoint.resumeMillis)
            assertEquals("entry-123", checkpoint.entryId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyQueueJsonWithoutEntryIdDeserializesCleanly() = runBlocking {
        val file = Files.createTempFile("openyt-playback-legacy-queue-", ".db").toFile()
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            // Insert legacy JSON produced before entryId was added to PlayableMedia
            val legacyJson = """{"items":[{"path":"/legacy/old.mp4","title":"Old Video","kind":"VIDEO"}],"currentIndex":0}"""
            database.downloadQueries.savePlaybackQueue(legacyJson)

            val store = SqlDelightPlaybackStore(database)
            val queue = store.loadQueue()
            assertNotNull(queue)
            assertEquals(1, queue.items.size)
            assertEquals("/legacy/old.mp4", queue.items[0].path)
            assertEquals("Old Video", queue.items[0].title)
            assertEquals(MediaKind.VIDEO, queue.items[0].kind)
            assertNull(queue.items[0].entryId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyPathRecordWithoutEntryIdReadsCleanlyAndBridgesOnLookup() = runBlocking {
        val file = Files.createTempFile("openyt-playback-legacy-pos-", ".db").toFile()
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            // Save position with entry_id = null (legacy row)
            database.downloadQueries.savePlaybackPosition("/legacy/media.mp4", 45_000, 120_000, null)

            val store = SqlDelightPlaybackStore(database)
            // Lookup without entryId reads legacy row
            val readLegacy = store.position("/legacy/media.mp4")
            assertNotNull(readLegacy)
            assertEquals(45_000, readLegacy.positionMillis)
            assertEquals(120_000, readLegacy.durationMillis)
            assertNull(readLegacy.entryId)

            // Lookup with entryId bridges the record
            val bridged = store.position("/legacy/media.mp4", "bridged-entry-99")
            assertNotNull(bridged)
            assertEquals(45_000, bridged.positionMillis)
            assertEquals("bridged-entry-99", bridged.entryId)

            // Verify row in DB was updated with the bridged entryId
            val byEntry = store.positionForEntry("bridged-entry-99")
            assertNotNull(byEntry)
            assertEquals(45_000, byEntry.positionMillis)
            assertEquals("bridged-entry-99", byEntry.entryId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun movingEntryLocatorRecoversCheckpointByEntryId() = runBlocking {
        val file = Files.createTempFile("openyt-playback-reloc-", ".db").toFile()
        try {
            val store = SqlDelightPlaybackStore(createOpenYtDatabase(file.absolutePath))
            // Save checkpoint at original path
            store.savePosition("/storage/downloads/lecture.mp4", PlaybackCheckpoint(30_000, 180_000), "entry-lecture-42")

            // Media file is relocated / relinked to a new path
            val newPath = "/storage/archive/lectures/lecture_renamed.mp4"

            // Lookup at new path with entryId retrieves the checkpoint from the old path!
            val recovered = store.position(newPath, "entry-lecture-42")
            assertNotNull(recovered)
            assertEquals(30_000, recovered.positionMillis)
            assertEquals(180_000, recovered.durationMillis)

            // Saving at new path updates the locator
            store.savePosition(newPath, PlaybackCheckpoint(35_000, 180_000), "entry-lecture-42")

            // Recheck: now new path has the updated checkpoint
            val updated = store.position(newPath, "entry-lecture-42")
            assertNotNull(updated)
            assertEquals(35_000, updated.positionMillis)

            // Old path record was cleaned up so entry locator doesn't leave duplicates
            val oldPathLookup = store.position("/storage/downloads/lecture.mp4")
            assertNull(oldPathLookup)
        } finally {
            file.delete()
        }
    }

    @Test
    fun fallbackForMediaWithoutEntryId() = runBlocking {
        val file = Files.createTempFile("openyt-playback-no-entry-", ".db").toFile()
        try {
            val store = SqlDelightPlaybackStore(createOpenYtDatabase(file.absolutePath))
            // Save external file without entry ID
            store.savePosition("/external/song.opus", PlaybackCheckpoint(60_000, 240_000), null)

            val checkpoint = store.position("/external/song.opus", null)
            assertNotNull(checkpoint)
            assertEquals(60_000, checkpoint.positionMillis)
            assertNull(checkpoint.entryId)

            // positionForEntry with random entryId returns null
            assertNull(store.positionForEntry("non-existent"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun inMemoryPlaybackStoreSupportsEntryBridgeAndMovingLocators() = runBlocking {
        val store = MemoryPlaybackStore()
        store.savePosition("/old/path.mp4", PlaybackCheckpoint(15_000, 60_000), "mem-entry-1")

        // Recover by entry at new path
        val recovered = store.position("/new/path.mp4", "mem-entry-1")
        assertNotNull(recovered)
        assertEquals(15_000, recovered.positionMillis)

        // Save at new path
        store.savePosition("/new/path.mp4", PlaybackCheckpoint(20_000, 60_000), "mem-entry-1")
        val updated = store.position("/new/path.mp4", "mem-entry-1")
        assertNotNull(updated)
        assertEquals(20_000, updated.positionMillis)

        // Null entry fallback
        store.savePosition("/standalone.mp3", PlaybackCheckpoint(5_000, 50_000), null)
        assertEquals(5_000, store.position("/standalone.mp3", null)?.positionMillis)
    }
}
