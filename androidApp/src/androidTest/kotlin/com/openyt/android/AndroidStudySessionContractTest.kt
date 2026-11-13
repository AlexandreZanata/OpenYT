package com.openyt.android

import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.player.AndroidPlaybackLauncher
import com.openyt.android.player.PlaybackService
import com.openyt.android.player.PlayerActivity
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.StudyCaptureAndNavigationService
import com.openyt.core.application.StudyService
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyNote
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.createOpenYtDatabase
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Android contract verification for NX-T02:
 * atomic Media3 snapshot capture, entry-bound note creation, and note
 * timestamp navigation via [AndroidPlaybackLauncher] / [PlaybackService].
 */
@RunWith(AndroidJUnit4::class)
class AndroidStudySessionContractTest {

    @Test
    fun capturesLivePositionAndNavigatesToSavedNoteOnAndroidMedia3() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dbName = "openyt-study-session-android.db"
        context.deleteDatabase(dbName)

        val fixture = File(context.cacheDir, "study-session-fixture.mp4")
        instrumentation.context.assets.open("player-fixture.mp4").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }

        val database = createOpenYtDatabase(context, dbName)
        val studyRepo = SqlDelightStudyRepository(database)
        val playbackStore = SqlDelightPlaybackStore(database)
        val studyService = StudyService(
            repository = studyRepo,
            nowEpochMillis = { System.currentTimeMillis() },
            nextId = { "android-note-1" },
        )

        val entry = LibraryEntry(
            id = "entry-android-study-1",
            title = "Android Study Fixture",
            kind = MediaKind.VIDEO,
            filePath = fixture.absolutePath,
            sizeBytes = fixture.length(),
            mediaRevision = MediaRevision(fixture.length(), fixture.lastModified(), "sha-study-android"),
        )
        database.downloadQueries.insertLibraryEntry(
            id = entry.id,
            title = entry.title,
            author = "OpenYT Android",
            kind = entry.kind.name,
            file_path = entry.filePath,
            size_bytes = entry.sizeBytes,
            thumbnail_url = null,
            source = "local",
            media_revision_size = entry.mediaRevision?.sizeBytes,
            media_revision_mtime = entry.mediaRevision?.lastModifiedEpochMillis,
            media_revision_sha256 = entry.mediaRevision?.contentSha256,
            last_verified_at = System.currentTimeMillis(),
        )

        val launcher = AndroidPlaybackLauncher(context, playbackStore)
        val navService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = launcher,
            playbackStore = playbackStore,
            entryProvider = { listOf(entry) },
            fileAvailability = FileAvailability { File(it).isFile },
            nowEpochMillis = { System.currentTimeMillis() },
        )

        val startIntent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra(PlayerActivity.EXTRA_PATHS, arrayListOf(fixture.absolutePath))
            putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf(entry.title))
            putStringArrayListExtra(PlayerActivity.EXTRA_KINDS, arrayListOf(entry.kind.name))
            putStringArrayListExtra(PlayerActivity.EXTRA_ENTRY_IDS, arrayListOf(entry.id))
            putExtra(PlayerActivity.EXTRA_START_INDEX, 0)
            putExtra(PlayerActivity.EXTRA_START_POSITION_MILLIS, 2_000L)
        }

        val activity = instrumentation.startActivitySync(startIntent) as PlayerActivity
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

            repeat(150) {
                val snap = launcher.captureSnapshot()
                if (snap != null &&
                    (snap.state == PlaybackSessionState.PLAYING || snap.state == PlaybackSessionState.PAUSED)
                ) {
                    return@repeat
                }
                Thread.sleep(100)
            }

            val captured = navService.captureAndCreateNote(
                body = "Android live note",
                expectedEntryId = entry.id,
            )
            assertTrue(captured is StudyCaptureResult.Success<StudyNote>)
            val savedNote = (captured as StudyCaptureResult.Success<StudyNote>).value
            assertEquals(entry.id, savedNote.entryId)
            val snap = launcher.captureSnapshot()
            assertNotNull(snap)
            assertTrue(abs(savedNote.timestampMillis - snap!!.positionMillis) <= 1_000L)

            val opened = navService.openNoteTimestamp(savedNote.id)
            assertTrue(opened is StudyNavigationResult.Opened)
        } finally {
            instrumentation.runOnMainSync {
                controller?.stop()
                controller?.clearMediaItems()
                controller?.release()
                activity.finish()
            }
            context.stopService(Intent(context, PlaybackService::class.java))
            fixture.delete()
            context.deleteDatabase(dbName)
        }
    }
}
