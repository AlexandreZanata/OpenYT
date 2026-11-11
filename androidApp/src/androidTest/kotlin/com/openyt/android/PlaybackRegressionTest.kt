package com.openyt.android

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.player.PlaybackService
import com.openyt.android.player.PlayerActivity
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackRegressionTest {
    @Test fun actualMedia3PlaybackEntersPictureInPicture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "player-regression.mp4")
        instrumentation.context.assets.open("player-fixture.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putStringArrayListExtra(PlayerActivity.EXTRA_PATHS, arrayListOf(fixture.absolutePath))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf("Playback regression"))
            .putStringArrayListExtra(PlayerActivity.EXTRA_KINDS, arrayListOf("VIDEO"))) as PlayerActivity
        var controller: MediaController? = null
        try {
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            instrumentation.runOnMainSync {
                future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            controller = future!!.get(30, TimeUnit.SECONDS)
            var playing = false
            repeat(150) {
                instrumentation.runOnMainSync { playing = controller.isPlaying }
                if (!playing) Thread.sleep(100)
            }
            assertTrue("Media3 must decode the real video", playing)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 3").close()
            var pip = false
            repeat(100) {
                instrumentation.runOnMainSync { pip = activity.isInPictureInPictureMode }
                if (!pip) Thread.sleep(100)
            }
            assertTrue("Leaving video playback must enter PiP", pip)
            val screenshot = File(context.getExternalFilesDir(null), "validation/android-pip.png").apply { parentFile!!.mkdirs() }
            screenshot.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            instrumentation.runOnMainSync { controller?.stop(); controller?.clearMediaItems(); controller?.release(); activity.finish() }
            context.stopService(Intent(context, PlaybackService::class.java))
            fixture.delete()
        }
    }
}
