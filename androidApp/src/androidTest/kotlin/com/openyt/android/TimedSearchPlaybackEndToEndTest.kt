package com.openyt.android

import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.player.PlaybackService
import com.openyt.android.player.PlayerActivity
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.TimedCue
import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.search.SqlDelightTimedSearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * End-to-end integration test proving the complete offline path on Android:
 * 1. Subtitle tracks indexed in local SQLite FTS4 with unicode61;
 * 2. Scoped text query finds the mapped synthetic cue without network access;
 * 3. Media3 PlayerActivity initiates playback seeking to the mapped timestamp;
 * 4. Actual player position is verified within one second (<= 1000 ms) of the cue start.
 *
 * Verifies contract NX-S07.
 */
@RunWith(AndroidJUnit4::class)
class TimedSearchPlaybackEndToEndTest {

    @Test
    fun androidOfflineSearchPhraseToRealPlayerTime() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dbName = "openyt-e2e-android-search.db"
        context.deleteDatabase(dbName)

        val fixture = File(context.cacheDir, "timed-search-e2e-fixture.mp4")
        instrumentation.context.assets.open("player-fixture.mp4").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }

        val database = createOpenYtDatabase(context, dbName)
        val indexRepo = SqlDelightSubtitleIndexRepository(database)
        val searchRepo = SqlDelightTimedSearchRepository(database)
        val useCase = TimedSearchUseCase(searchRepo)

        val trackId = "track-android-pt"
        val entryId = "entry-android-video-1"
        val timebaseOffsetMs = 0L

        try {
            // 0. Register media entry in library
            database.downloadQueries.insertLibraryEntry(
                id = entryId,
                title = "Android E2E Playback",
                author = "OpenYT Android",
                kind = MediaKind.VIDEO.name,
                file_path = fixture.absolutePath,
                size_bytes = fixture.length(),
                thumbnail_url = null,
                source = "local",
                media_revision_size = fixture.length(),
                media_revision_mtime = fixture.lastModified(),
                media_revision_sha256 = "sha-android-pt",
                last_verified_at = System.currentTimeMillis(),
            )

            // 1. Index synthetic Portuguese cues into SQLite FTS4
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
                mediaRevisionToken = "rev-media-android-1",
                trackRevision = "rev-track-android-1",
            )
            indexRepo.persistCues(ptCues, trackId, entryId, rev)
            indexRepo.commitRevision(trackId, rev, ptCues.size, timebaseOffsetMs)

            // 2. Offline search for synthetic phrase: "pesquisa sincronizada"
            val searchResult = useCase.execute(
                query = "pesquisa sincronizada",
                scope = TimedSearchScope.Entry(entryId),
                filter = TimedSearchFilter(language = "pt-BR"),
            )

            assertTrue("Search must succeed for synthetic phrase", searchResult is TimedSearchResult.Success)
            val success = searchResult as TimedSearchResult.Success
            assertEquals(1, success.totalMatches)
            val match = success.items.first()
            assertEquals(entryId, match.entryId)
            assertEquals(3_000L, match.startMs)

            // 3. Launch Media3 PlayerActivity seeking to cue start
            val targetPosition = (match.startMs + match.timebaseOffsetMs).coerceAtLeast(0L)
            assertEquals(3_000L, targetPosition)

            val intent = Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_PATHS, arrayListOf(fixture.absolutePath))
                putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf("Android E2E Playback"))
                putStringArrayListExtra(PlayerActivity.EXTRA_KINDS, arrayListOf(MediaKind.VIDEO.name))
                putStringArrayListExtra(PlayerActivity.EXTRA_ENTRY_IDS, arrayListOf(entryId))
                putExtra(PlayerActivity.EXTRA_START_INDEX, 0)
                putExtra(PlayerActivity.EXTRA_START_POSITION_MILLIS, targetPosition)
            }

            val activity = instrumentation.startActivitySync(intent) as PlayerActivity
            var controller: MediaController? = null

            try {
                var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
                instrumentation.runOnMainSync {
                    future = MediaController.Builder(
                        context,
                        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                    ).buildAsync()
                }

                controller = future!!.get(30, TimeUnit.SECONDS)
                assertNotNull("MediaController must connect to PlaybackService", controller)

                var playing = false
                var currentPosition = 0L
                repeat(150) {
                    instrumentation.runOnMainSync {
                        playing = controller.isPlaying
                        currentPosition = controller.currentPosition
                    }
                    if (playing && abs(currentPosition - targetPosition) <= 1_000L) {
                        return@repeat
                    }
                    Thread.sleep(100)
                }

                // Confirm player position is within 1 second of cue start (3_000 ms)
                val diff = abs(currentPosition - targetPosition)
                assertTrue(
                    "Media3 player position ($currentPosition ms) must be within 1s of cue start ($targetPosition ms), diff was $diff ms",
                    diff <= 1_000L,
                )

                // Capture validation screenshot
                saveValidationScreenshot("android-timed-search-playback.png")
            } finally {
                instrumentation.runOnMainSync {
                    controller?.stop()
                    controller?.clearMediaItems()
                    controller?.release()
                    activity.finish()
                }
                context.stopService(Intent(context, PlaybackService::class.java))
            }
        } finally {
            fixture.delete()
            context.deleteDatabase(dbName)
        }
    }
}
