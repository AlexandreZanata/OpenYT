package com.openyt.desktop.search

import com.openyt.core.application.MediaActions
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ShareableMedia
import com.openyt.core.domain.TimedCue
import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.search.SqlDelightTimedSearchRepository
import com.openyt.desktop.player.DesktopPlaybackLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test proving the complete offline path:
 * 1. Subtitle tracks indexed in local SQLite FTS4 with unicode61;
 * 2. Scoped text query finds the mapped synthetic cue without network access;
 * 3. Playback launcher initiates session seek to the mapped timestamp (plus timebase offset)
 *    confirming player position is within one second (<= 1000 ms) of the cue start;
 * 4. Close & reopen cycle verifies checkpoint persistence and session resume tolerance.
 *
 * Verifies contract NX-S07.
 */
class TimedSearchPlaybackEndToEndTest {

    private val testActions = object : MediaActions {
        override val canOpen = true
        override val canShare = false
        override val canReveal = false
        override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit
        override fun reveal(media: ShareableMedia) = false
        override fun open(media: ShareableMedia): Boolean = true
    }

    @Test
    fun offlineSearchPhraseToRealPlayerTimeAndCheckpointResume() = runBlocking {
        val tempDb = File.createTempFile("openyt-e2e-search-playback-", ".db").apply { delete() }
        val tempMedia = File.createTempFile("openyt-synthetic-video-", ".mp4").apply {
            writeBytes(ByteArray(1024) { 0 }) // Synthetic inert media fixture
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val database = createOpenYtDatabase(tempDb.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)
            val playbackStore = SqlDelightPlaybackStore(database)
            val useCase = TimedSearchUseCase(searchRepo)

            val trackId = "track-e2e-pt"
            val entryId = "entry-e2e-video-1"
            val timebaseOffsetMs = 250L // 250ms offset

            // 0. Register media entry in library
            database.downloadQueries.insertLibraryEntry(
                id = entryId,
                title = "Synthetic Video",
                author = "OpenYT Testing",
                kind = MediaKind.VIDEO.name,
                file_path = tempMedia.absolutePath,
                size_bytes = tempMedia.length(),
                thumbnail_url = null,
                source = "synthetic",
                media_revision_size = tempMedia.length(),
                media_revision_mtime = tempMedia.lastModified(),
                media_revision_sha256 = "sha-synthetic-pt",
                last_verified_at = System.currentTimeMillis(),
            )

            // 1. Index synthetic Portuguese cues into SQLite + FTS4
            val ptCues = listOf(
                TimedCue(
                    cueId = "cue-1",
                    trackId = trackId,
                    entryId = entryId,
                    startMs = 1_000L,
                    endMs = 4_500L,
                    displayText = "Introdução à biblioteca pessoal de mídia do OpenYT.",
                    plainText = "Introdução à biblioteca pessoal de mídia do OpenYT.",
                    speaker = "Narrador",
                    originalIndex = 1,
                ),
                TimedCue(
                    cueId = "cue-2",
                    trackId = trackId,
                    entryId = entryId,
                    startMs = 3_000L,
                    endMs = 7_000L,
                    displayText = "Lição prática: pesquisa sincronizada e anotações com acentuação.",
                    plainText = "Lição prática: pesquisa sincronizada e anotações com acentuação.",
                    speaker = "Professor",
                    originalIndex = 2,
                ),
                TimedCue(
                    cueId = "cue-3",
                    trackId = trackId,
                    entryId = entryId,
                    startMs = 7_500L,
                    endMs = 9_800L,
                    displayText = "Preservação offline concluída com sucesso.",
                    plainText = "Preservação offline concluída com sucesso.",
                    speaker = "Narrador",
                    originalIndex = 3,
                ),
            )

            val rev = indexRepo.beginRevision(
                trackId = trackId,
                entryId = entryId,
                language = "pt-BR",
                mediaRevisionToken = "rev-media-1",
                trackRevision = "rev-track-1",
            )
            indexRepo.persistCues(ptCues, trackId, entryId, rev)
            indexRepo.commitRevision(trackId, rev, ptCues.size, timebaseOffsetMs)

            // 2. Search synthetic phrase with accents: "pesquisa sincronizada" (Offline, zero network)
            val searchResult = useCase.execute(
                query = "pesquisa sincronizada",
                scope = TimedSearchScope.Entry(entryId),
                filter = TimedSearchFilter(language = "pt-BR"),
            )

            assertTrue(searchResult is TimedSearchResult.Success, "Search must succeed for synthetic phrase")
            assertEquals(1, searchResult.totalMatches, "Must find exactly 1 matching cue")
            val match = searchResult.items.first()
            assertEquals(entryId, match.entryId)
            assertEquals(3_000L, match.startMs)
            assertEquals(timebaseOffsetMs, match.timebaseOffsetMs)
            assertTrue(match.excerpt.text.contains("pesquisa sincronizada"))

            // 3. Launch playback at mapped cue position
            val targetPosition = (match.startMs + match.timebaseOffsetMs).coerceAtLeast(0L)
            assertEquals(3_250L, targetPosition)

            val launcher = DesktopPlaybackLauncher(
                store = playbackStore,
                scope = scope,
                mediaActions = testActions,
                runtimeAvailable = { false }, // Uses internal session controller with persistent store
            )

            try {
                val playable = PlayableMedia(
                    path = tempMedia.absolutePath,
                    title = "Synthetic Video",
                    kind = MediaKind.VIDEO,
                    entryId = match.entryId,
                )

                val opened = launcher.openAt(listOf(playable), 0, startPositionMillis = targetPosition)
                assertTrue(opened, "Playback session must open successfully")
                assertEquals(playable, launcher.loadedMedia)

                // Confirm player start position is within one second (<= 1000 ms) of the mapped cue start
                val positionDiff = abs(launcher.currentPositionMillis - match.startMs)
                assertTrue(
                    positionDiff <= 1_000L,
                    "Actual player position (${launcher.currentPositionMillis} ms) must be within 1 second of cue start (${match.startMs} ms), diff was $positionDiff ms",
                )

                // 4. Close playback session and verify persistent checkpoint
                launcher.close()
                assertEquals(PlaybackSessionState.IDLE, launcher.state)

                val checkpoint = playbackStore.position(playable.path, playable.entryId)
                assertNotNull(checkpoint, "Checkpoint must be persisted in SQLite")
                assertEquals(playable.entryId, checkpoint.entryId)
                val checkpointDiff = abs(checkpoint.resumeMillis - match.startMs)
                assertTrue(
                    checkpointDiff <= 1_000L,
                    "Persisted checkpoint (${checkpoint.resumeMillis} ms) must be within 1 second of cue start (${match.startMs} ms)",
                )

                // 5. Resume last session and confirm position preserved within 1 second tolerance
                val resumed = launcher.resumeLastSession()
                assertTrue(resumed, "Session resume must succeed")
                assertEquals(playable.path, launcher.loadedMedia?.path)
                val resumeDiff = abs(launcher.currentPositionMillis - match.startMs)
                assertTrue(
                    resumeDiff <= 1_000L,
                    "Resumed position (${launcher.currentPositionMillis} ms) must be within 1 second of cue start (${match.startMs} ms)",
                )
            } finally {
                launcher.close()
            }
        } finally {
            scope.cancel()
            tempDb.delete()
            tempMedia.delete()
        }
    }

    @Test
    fun englishSyntheticPhraseSearchAndSeekAccuracy() = runBlocking {
        val tempDb = File.createTempFile("openyt-e2e-en-search-", ".db").apply { delete() }
        val tempMedia = File.createTempFile("openyt-synthetic-video-en-", ".mp4").apply {
            writeBytes(ByteArray(1024) { 0 })
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val database = createOpenYtDatabase(tempDb.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)
            val playbackStore = SqlDelightPlaybackStore(database)
            val useCase = TimedSearchUseCase(searchRepo)

            val trackId = "track-e2e-en"
            val entryId = "entry-e2e-en-2"

            // 0. Register media entry in library
            database.downloadQueries.insertLibraryEntry(
                id = entryId,
                title = "English Video",
                author = "OpenYT Testing",
                kind = MediaKind.VIDEO.name,
                file_path = tempMedia.absolutePath,
                size_bytes = tempMedia.length(),
                thumbnail_url = null,
                source = "synthetic",
                media_revision_size = tempMedia.length(),
                media_revision_mtime = tempMedia.lastModified(),
                media_revision_sha256 = "sha-synthetic-en",
                last_verified_at = System.currentTimeMillis(),
            )

            val enCues = listOf(
                TimedCue(
                    cueId = "c1",
                    trackId = trackId,
                    entryId = entryId,
                    startMs = 1_000L,
                    endMs = 4_500L,
                    displayText = "Introduction to OpenYT personal media library.",
                    plainText = "Introduction to OpenYT personal media library.",
                    originalIndex = 1,
                ),
                TimedCue(
                    cueId = "c2",
                    trackId = trackId,
                    entryId = entryId,
                    startMs = 3_000L,
                    endMs = 7_000L,
                    displayText = "Practical study: timed search and study notes.",
                    plainText = "Practical study: timed search and study notes.",
                    originalIndex = 2,
                ),
                TimedCue(
                    cueId = "c3",
                    trackId = trackId,
                    entryId = entryId,
                    startMs = 7_500L,
                    endMs = 9_800L,
                    displayText = "Offline preservation completed successfully.",
                    plainText = "Offline preservation completed successfully.",
                    originalIndex = 3,
                ),
            )

            val rev = indexRepo.beginRevision(trackId, entryId, "en", "rev-en-m", "rev-en-t")
            indexRepo.persistCues(enCues, trackId, entryId, rev)
            indexRepo.commitRevision(trackId, rev, enCues.size, 0L)

            // Search "timed search"
            val searchResult = useCase.execute(
                query = "timed search",
                scope = TimedSearchScope.Library,
                filter = TimedSearchFilter(language = "en"),
            )

            assertTrue(searchResult is TimedSearchResult.Success)
            assertEquals(1, searchResult.totalMatches)
            val match = searchResult.items.first()
            assertEquals(3_000L, match.startMs)

            val launcher = DesktopPlaybackLauncher(
                store = playbackStore,
                scope = scope,
                mediaActions = testActions,
                runtimeAvailable = { false },
            )
            try {
                val playable = PlayableMedia(tempMedia.absolutePath, "English Video", MediaKind.VIDEO, entryId = entryId)
                launcher.openAt(listOf(playable), 0, startPositionMillis = match.startMs)

                val diff = abs(launcher.currentPositionMillis - match.startMs)
                assertTrue(diff <= 1_000L, "Playback position must be within 1s of cue start")
            } finally {
                launcher.close()
            }
        } finally {
            scope.cancel()
            tempDb.delete()
            tempMedia.delete()
        }
    }
}
