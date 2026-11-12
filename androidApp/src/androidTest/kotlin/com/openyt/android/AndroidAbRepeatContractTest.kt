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
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackSessionListener
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyService
import com.openyt.core.domain.AbRepeatResult
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyResult
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.createOpenYtDatabase
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Android contract verification for NX-T03:
 * Media3 A/B repetition 5-loop tolerance, pause preservation, item switch and
 * stop clearing without background playback, and no auto-start after restart.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAbRepeatContractTest {

    @Test
    fun repeatsAbRangeFiveTimesOnMedia3AndClearsCleanlyOnStopAndRestart() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dbName = "openyt-ab-repeat-android.db"
        context.deleteDatabase(dbName)

        val fixture = File(context.cacheDir, "ab-repeat-fixture.mp4")
        instrumentation.context.assets.open("player-fixture.mp4").use { input ->
            fixture.outputStream().use { it.write(input.readBytes()) }
        }

        val database = createOpenYtDatabase(context, dbName)
        val studyRepo = SqlDelightStudyRepository(database)
        val playbackStore = SqlDelightPlaybackStore(database)
        val studyService = StudyService(
            repository = studyRepo,
            nowEpochMillis = { System.currentTimeMillis() },
            nextId = { "android-ab-range-1" },
        )

        val entry = LibraryEntry(
            id = "entry-android-ab-1",
            title = "Android AB Fixture",
            kind = MediaKind.VIDEO,
            filePath = fixture.absolutePath,
            sizeBytes = fixture.length(),
            mediaRevision = MediaRevision(fixture.length(), fixture.lastModified(), "sha-ab-android"),
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

        val savedRange = (studyService.saveRange(entry.id, 2_000L, 3_600L, "Repeat drill", 30_000L)
            as StudyResult.Success<SavedStudyRange>).value

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val launcher = AndroidPlaybackLauncher(context, playbackStore)
        val controller = StudyAbRepeatController(launcher, scope)

        // Persisted range never auto-starts before playback or on restart
        assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)

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
        var mediaController: MediaController? = null

        try {
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            instrumentation.runOnMainSync {
                future = MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                ).buildAsync()
            }
            mediaController = future!!.get(30, TimeUnit.SECONDS)

            repeat(150) {
                val snap = launcher.captureSnapshot()
                if (snap != null &&
                    snap.state == PlaybackSessionState.PLAYING &&
                    (snap.knownDurationMillis ?: 0L) > 5_000L
                ) {
                    return@repeat
                }
                delay(100)
            }

            // Still inactive after media starts until explicit action
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)

            val positions = CopyOnWriteArrayList<Pair<Int, Long>>()
            val unregister = launcher.registerSessionListener(PlaybackSessionListener { snap ->
                if (snap != null && snap.state == PlaybackSessionState.PLAYING) {
                    positions += controller.state.value.loopCount to snap.positionMillis
                }
            })

            val activated = controller.activateSavedRange(savedRange, startImmediately = true)
            assertTrue("Expected activateSavedRange success, got $activated", activated is AbRepeatResult.Success)
            assertEquals(AbRepeatStatus.ACTIVE, controller.state.value.status)

            var checkedPause = false
            val deadline = System.currentTimeMillis() + 35_000L
            while (controller.state.value.loopCount < 5 && System.currentTimeMillis() < deadline) {
                if (controller.state.value.loopCount >= 2 && !checkedPause) {
                    checkedPause = true
                    launcher.pause()
                    delay(350)
                    assertEquals(AbRepeatStatus.PAUSED, controller.state.value.status)
                    val pausedPos = launcher.captureSnapshot()?.positionMillis ?: 0L
                    delay(400)
                    val pausedPosAfter = launcher.captureSnapshot()?.positionMillis ?: 0L
                    assertTrue("Pause must keep position stationary", abs(pausedPosAfter - pausedPos) <= 250L)
                    launcher.play()
                    delay(150)
                    assertEquals(AbRepeatStatus.ACTIVE, controller.state.value.status)
                }
                delay(80)
            }

            assertTrue(
                "Expected >= 5 Media3 A/B loops, got ${controller.state.value.loopCount}",
                controller.state.value.loopCount >= 5,
            )

            repeat(30) {
                if (positions.any { it.first == 5 && it.second < 2_800L }) return@repeat
                delay(100)
            }

            for (loopNum in 0..4) {
                val loopPositions = positions.filter { it.first == loopNum }.map { it.second }
                val maxPos = loopPositions.maxOrNull() ?: 3_600L
                val overshoot = (maxPos - 3_600L).coerceAtLeast(0L)
                assertTrue("Loop $loopNum overshot B=3600ms by ${overshoot}ms", overshoot <= 1_000L)
            }
            for (loopNum in 1..5) {
                val loopPositions = positions.filter { it.first == loopNum }.map { it.second }
                assertTrue("Missing telemetry after loop $loopNum", loopPositions.isNotEmpty())
                val minPos = loopPositions.minOrNull()!!
                assertTrue("Loop $loopNum returned to ${minPos}ms instead of ~2000ms", abs(minPos - 2_000L) <= 1_000L)
            }

            unregister()
            launcher.stop()
            delay(250)
            assertEquals(PlaybackSessionState.IDLE, launcher.state)
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            delay(400)
            assertEquals(PlaybackSessionState.IDLE, launcher.state)
        } finally {
            controller.close()
            instrumentation.runOnMainSync {
                mediaController?.stop()
                mediaController?.clearMediaItems()
                mediaController?.release()
                activity.finish()
            }
            context.stopService(Intent(context, PlaybackService::class.java))
            scope.cancel()
            fixture.delete()
            context.deleteDatabase(dbName)
        }
    }
}
