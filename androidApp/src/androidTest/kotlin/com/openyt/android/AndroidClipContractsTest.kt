package com.openyt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.player.androidMediaProcessor
import com.openyt.android.probe.AndroidMediaProbe
import com.openyt.core.application.*
import com.openyt.core.domain.*
import com.openyt.data.*
import com.openyt.data.db.OpenYtDatabase
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class AndroidClipContractsTest {
    @Test fun actualBundledFfmpegExportsClipsAndPublishesReceiptsWithoutChangingOriginal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "clip-contract-$id").apply { mkdirs() }
        val databaseName = "clip-contract-$id.db"
        val driver = createOpenYtDriver(context, databaseName)
        try {
            val database = OpenYtDatabase(driver)
            val source = File(root, "Vídeo d'ação #1.mp4")
            instrumentation.context.assets.open("player-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
            fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
            val original = digest(source)
            val assets = JvmOfflineAssetStore(root.resolve("assets"), SqlDelightOfflineAssetRepository(database), SqlDelightAssetOperationRepository(database))
            val processor = androidMediaProcessor(context, SqlDelightClipJobStore(database), assets)
            val caps = processor.capabilities()
            android.util.Log.i("OpenYTContracts", "Android FFmpeg capabilities: $caps")
            val duration = checkNotNull(AndroidMediaProbe().probe(source.path)?.durationMillis)
            val base = MediaProcessingRequest(source.path, "Native clip", MediaKind.VIDEO, MediaOperation.TRIM,
                MediaSegment(0, minOf(3_000, duration)), sourceEntryId = "source", sourceDurationMillis = duration, jobId = "copy-$id")
            val requests = mutableListOf(base, base.copy(jobId = "second-$id", segment = MediaSegment(1_000, minOf(4_000, duration))))
            if (caps.preciseClips && caps.portrait) requests += base.copy(jobId = "portrait-$id", operation = MediaOperation.RENDER_CLIP, framing = ClipFraming.FIT_PORTRAIT,
                captions = if (caps.burnCaptions) listOf(ClipCaption(0, 2500, "AÇÃO d'água")) else emptyList(), coverAtMillis = if (caps.covers) 1000 else null)
            assertTrue(processor.queueClips(requests))
            val outputs = requests.map { (processor.process(it) as? EngineResult.Success)?.value ?: error("Bundled FFmpeg clip export failed") }
            outputs.forEach { assertTrue(File(it.path).length() > 0); assertTrue((AndroidMediaProbe().probe(it.path)?.durationMillis ?: 0) > 0) }
            if (caps.preciseClips && caps.portrait) {
                val portrait = AndroidMediaProbe().probe(outputs.last().path)!!
                assertEquals(720, portrait.width)
                assertEquals(1280, portrait.height)
                if (caps.covers) assertEquals(1, assets.getAssetsForEntry(outputs.last().id).size)
            }
            assertEquals(outputs.map { it.id }.toSet(), SqlDelightDownloadRepository(database).loadLibrary().map { it.id }.toSet())
            val activity = instrumentation.startActivitySync(android.content.Intent(context, com.openyt.android.player.PlayerActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putStringArrayListExtra(com.openyt.android.player.PlayerActivity.EXTRA_PATHS, ArrayList(outputs.map { it.path }))
                .putStringArrayListExtra(com.openyt.android.player.PlayerActivity.EXTRA_TITLES, ArrayList(outputs.indices.map { "Exported clip ${it + 1}" }))
                .putStringArrayListExtra(com.openyt.android.player.PlayerActivity.EXTRA_KINDS, ArrayList(outputs.map { "VIDEO" }))) as com.openyt.android.player.PlayerActivity
            var controller: androidx.media3.session.MediaController? = null
            try {
                var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
                instrumentation.runOnMainSync {
                    future = androidx.media3.session.MediaController.Builder(context, androidx.media3.session.SessionToken(context,
                        android.content.ComponentName(context, com.openyt.android.player.PlaybackService::class.java))).buildAsync()
                }
                controller = future!!.get(20, TimeUnit.SECONDS)
                var queued = false
                val queueClock = System.nanoTime()
                while (!queued && System.nanoTime() - queueClock < TimeUnit.SECONDS.toNanos(10)) {
                    instrumentation.runOnMainSync { queued = controller!!.mediaItemCount == outputs.size }
                    if (!queued) Thread.sleep(50)
                }
                assertTrue("The exported clip queue must load", queued)
                for (index in outputs.indices) {
                    instrumentation.runOnMainSync { controller!!.seekTo(index, 0); controller!!.prepare(); controller!!.play() }
                    var playing = false
                    val clock = System.nanoTime()
                    while (!playing && System.nanoTime() - clock < TimeUnit.SECONDS.toNanos(10)) {
                        instrumentation.runOnMainSync { playing = controller!!.currentMediaItemIndex == index && controller!!.isPlaying && controller!!.currentPosition >= 100 }
                        if (!playing) Thread.sleep(50)
                    }
                    assertTrue("The actual Media3 player must play exported clip ${index + 1}", playing)
                }
                saveValidationScreenshot("android-batch-clip-playing.png")
            } finally {
                instrumentation.runOnMainSync { controller?.stop(); controller?.clearMediaItems(); controller?.release(); activity.finish() }
                context.stopService(android.content.Intent(context, com.openyt.android.player.PlaybackService::class.java))
                instrumentation.waitForIdleSync()
            }
            assertEquals(outputs.first(), (processor.process(base) as EngineResult.Success).value)
            assertEquals(original, digest(source))
        } finally { driver.close(); context.deleteDatabase(databaseName); root.deleteRecursively() }
    }
}
