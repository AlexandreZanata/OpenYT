package com.openyt.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.player.AndroidPlaybackLauncher
import com.openyt.android.player.PlaybackService
import com.openyt.android.player.PlayerActivity
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.OpenYtEntryLink
import com.openyt.core.application.OpenYtLink
import com.openyt.core.application.OpenYtRoute
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyCaptureAndNavigationService
import com.openyt.core.application.StudyExportOptions
import com.openyt.core.application.StudyHostLinkCapability
import com.openyt.core.application.StudyMarkdownExporter
import com.openyt.core.application.StudyService
import com.openyt.core.domain.AbRepeatResult
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.Collection
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyResult
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.createOpenYtDatabase
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full NX-T06 end-to-end instrumented Android contract covering:
 * Media3 live note capture & A/B repeat, manual studied completion surviving
 * playback-end checkpoint reset, offline SQLite reopen (no auto-play/auto-loop),
 * note timestamp seek verification, safe Markdown export, openyt://library/entry
 * manifest dispatch validation, and original-preserving record removal.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStudyFlowContractTest {

    @Test
    fun fullOfflineStudyWorkflowExportsResolvesDeepLinkAndPreservesOriginalMedia() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dbName = "openyt-study-full-flow.db"
        context.deleteDatabase(dbName)

        val fixture = File(context.cacheDir, "study-e2e-android.mp4")
        instrumentation.context.assets.open("player-fixture.mp4").use { input ->
            fixture.outputStream().use { it.write(input.readBytes()) }
        }
        val initialHash = sha256Hex(fixture)

        val db1 = createOpenYtDatabase(context, dbName)
        val downloadRepo1 = SqlDelightDownloadRepository(db1)
        val studyRepo1 = SqlDelightStudyRepository(db1)
        val playbackStore1 = SqlDelightPlaybackStore(db1)
        var clock = 1_700_000_050_000L
        var seq = 0
        val studyService1 = StudyService(
            repository = studyRepo1,
            nowEpochMillis = { ++clock },
            nextId = { "android-e2e-${++seq}" },
        )

        val entry = LibraryEntry(
            id = "android-study 01",
            title = "Android <Study> [E2E]",
            kind = MediaKind.VIDEO,
            filePath = fixture.absolutePath,
            sizeBytes = fixture.length(),
            mediaRevision = MediaRevision(fixture.length(), fixture.lastModified(), initialHash),
        )
        downloadRepo1.insertLibraryEntry(entry)
        downloadRepo1.insertCollection(Collection("col-android-study", "Android Study"))
        downloadRepo1.addEntryToCollection("col-android-study", entry.id)

        val savedRange = (studyService1.saveRange(entry.id, 1_500L, 3_500L, "Android loop", 30_000L)
            as StudyResult.Success<SavedStudyRange>).value

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val launcher1 = AndroidPlaybackLauncher(context, playbackStore1)
        val controller1 = StudyAbRepeatController(launcher1, scope)
        val nav1 = StudyCaptureAndNavigationService(
            studyService = studyService1,
            playbackLauncher = launcher1,
            playbackStore = playbackStore1,
            entryProvider = { downloadRepo1.loadLibrary() },
            fileAvailability = FileAvailability { File(it).isFile },
            nowEpochMillis = { ++clock },
        )

        val startIntent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra(PlayerActivity.EXTRA_PATHS, arrayListOf(fixture.absolutePath))
            putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf(entry.title))
            putStringArrayListExtra(PlayerActivity.EXTRA_KINDS, arrayListOf(entry.kind.name))
            putStringArrayListExtra(PlayerActivity.EXTRA_ENTRY_IDS, arrayListOf(entry.id))
            putExtra(PlayerActivity.EXTRA_START_INDEX, 0)
            putExtra(PlayerActivity.EXTRA_START_POSITION_MILLIS, 2_200L)
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
                val snap = launcher1.captureSnapshot()
                if (snap != null &&
                    snap.state == PlaybackSessionState.PLAYING &&
                    (snap.knownDurationMillis ?: 0L) >= 5_000L
                ) {
                    return@repeat
                }
                delay(100)
            }

            // Capture live note
            val captured = nav1.captureAndCreateNote(
                body = "Android live note <safe> & check",
                expectedEntryId = entry.id,
            )
            assertTrue(captured is StudyCaptureResult.Success<StudyNote>)
            val note = (captured as StudyCaptureResult.Success<StudyNote>).value

            // Exercise Media3 A/B repeat briefly & clear cleanly before simulated restart
            assertTrue(controller1.activateSavedRange(savedRange, startImmediately = true) is AbRepeatResult.Success)
            val abDeadline = System.currentTimeMillis() + 20_000L
            while (controller1.state.value.loopCount < 2 && System.currentTimeMillis() < abDeadline) {
                delay(80)
            }
            assertTrue("Expected Media3 A/B repeat >= 2 loops", controller1.state.value.loopCount >= 2)
            launcher1.stop()
            delay(200)
            assertEquals(PlaybackSessionState.IDLE, launcher1.state)
            assertEquals(AbRepeatStatus.INACTIVE, controller1.state.value.status)

            // Mark studied + reset resume position at end
            assertTrue(studyService1.setEntryStudied(entry.id, true) is StudyResult.Success)
            playbackStore1.savePosition(
                entry.filePath,
                PlaybackCheckpoint(positionMillis = 29_000L, durationMillis = 30_000L, entryId = entry.id),
                entry.id,
            )
            assertEquals(0L, playbackStore1.position(entry.filePath, entry.id)?.resumeMillis)
            controller1.close()

            // Reopen SQLite database cleanly
            val db2 = createOpenYtDatabase(context, dbName)
            val downloadRepo2 = SqlDelightDownloadRepository(db2)
            val studyRepo2 = SqlDelightStudyRepository(db2)
            val playbackStore2 = SqlDelightPlaybackStore(db2)
            val studyService2 = StudyService(studyRepo2, nowEpochMillis = { ++clock })
            val launcher2 = AndroidPlaybackLauncher(context, playbackStore2)
            val controller2 = StudyAbRepeatController(launcher2, scope)
            val nav2 = StudyCaptureAndNavigationService(
                studyService = studyService2,
                playbackLauncher = launcher2,
                playbackStore = playbackStore2,
                entryProvider = { downloadRepo2.loadLibrary() },
                fileAvailability = FileAvailability { File(it).isFile },
                nowEpochMillis = { ++clock },
            )

            // Reopened DB must NOT auto-start playback or repeat
            assertEquals(AbRepeatStatus.INACTIVE, controller2.state.value.status)
            val bundle2 = studyService2.loadEntryStudyBundle(entry.id)
            assertTrue(bundle2.studyState.isStudied)
            assertEquals(1, bundle2.notes.size)
            assertEquals(1, studyService2.collectionProgress("col-android-study")?.studiedCount)

            // Reopen note -> seeks Media3 to exact timestamp
            assertTrue(nav2.openNoteTimestamp(note.id) is StudyNavigationResult.Opened)
            var matched = false
            repeat(60) {
                val snap = launcher2.captureSnapshot()
                if (snap != null &&
                    (snap.state == PlaybackSessionState.PLAYING || snap.state == PlaybackSessionState.PAUSED) &&
                    abs(snap.positionMillis - note.timestampMillis) <= 1_000L
                ) {
                    matched = true
                    return@repeat
                }
                delay(100)
            }
            assertTrue("Android note seek did not match saved timestamp", matched)

            // Safe Markdown export & Android IntentFilter verification for openyt://library/entry/...
            val markdown = StudyMarkdownExporter.exportEntryBundle(
                entry = entry,
                bundle = bundle2,
                options = StudyExportOptions(
                    includeLocalLinks = true,
                    language = AppLanguage.EN,
                ),
                hostCapability = StudyHostLinkCapability(supportsClickableEntryLinks = true, hostLabel = "android"),
            )
            assertFalse(fixture.absolutePath in markdown)
            assertFalse("<safe>" in markdown)
            val expectedLink = OpenYtEntryLink.create(entry.id, note.timestampMillis)!!
            assertTrue(expectedLink in markdown)

            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(expectedLink)).apply {
                `package` = context.packageName
            }
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(viewIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(viewIntent, 0)
            }
            assertNotNull("AndroidManifest must resolve openyt://library/entry/ URI to MainActivity", resolved)
            assertEquals(MainActivity::class.java.name, resolved!!.activityInfo.name)

            val parsedRoute = OpenYtLink.parseRoute(expectedLink)
            assertTrue(parsedRoute is OpenYtRoute.LibraryEntry)
            val entryRoute = parsedRoute as OpenYtRoute.LibraryEntry
            assertEquals(entry.id, entryRoute.entryId)
            assertEquals(note.timestampMillis, entryRoute.timestampMillis)

            launcher2.stop()
            controller2.close()

            // Removal preview & study purge preserve physical fixture bytes
            val db3 = createOpenYtDatabase(context, dbName)
            val downloadRepo3 = SqlDelightDownloadRepository(db3)
            val studyService3 = StudyService(SqlDelightStudyRepository(db3))
            val preview = studyService3.previewEntryRemoval(entry.id)
            assertTrue(preview.requiresConfirmationPrompt)
            downloadRepo3.deleteLibraryEntry(entry.id)
            assertEquals(1, studyService3.loadEntryStudyBundle(entry.id).notes.size)
            studyService3.deleteAllStudyDataForEntry(entry.id)
            assertEquals(0, studyService3.loadEntryStudyBundle(entry.id).notes.size)

            assertTrue(fixture.exists())
            assertEquals(initialHash, sha256Hex(fixture))
        } finally {
            controller1.close()
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

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
