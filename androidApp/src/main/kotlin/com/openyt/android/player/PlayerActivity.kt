package com.openyt.android.player

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.openyt.android.OpenYtApplication
import com.openyt.core.domain.MediaKind
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.stringsFor
import com.openyt.ui.theme.OpenYtTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var inPip by mutableStateOf(false)
    private var pipReady by mutableStateOf(false)
    private var title by mutableStateOf("")
    private var playbackFailed by mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val container get() = (application as OpenYtApplication).container
    private val strings get() = stringsFor(container.settingsController.settings.value.language, resources.configuration.locales[0].toLanguageTag())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val kinds = intent.getStringArrayListExtra(EXTRA_KINDS).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, paths.lastIndex.coerceAtLeast(0))
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (isFinishing || isDestroyed) return@addListener
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    title = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
                    playbackFailed = false
                    updatePip()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { updatePip() }
                override fun onPlayerError(error: PlaybackException) { playbackFailed = true; updatePip() }
            })
            scope.launch {
                if (paths.isNotEmpty()) {
                    val uris = paths.map { Uri.fromFile(File(it)) }
                    val sameQueue = connected.mediaItemCount == uris.size &&
                        uris.indices.all { connected.getMediaItemAt(it).localConfiguration?.uri == uris[it] }
                    if (sameQueue && connected.currentMediaItemIndex != startIndex) {
                        connected.seekTo(startIndex, container.playbackStore.position(paths[startIndex])?.resumeMillis ?: 0)
                    }
                    if (!sameQueue) {
                        val position = container.playbackStore.position(paths[startIndex])?.resumeMillis ?: 0
                        connected.setMediaItems(paths.mapIndexed { index, path ->
                            MediaItem.Builder().setUri(uris[index])
                                .setMediaMetadata(MediaMetadata.Builder()
                                    .setTitle(titles.getOrNull(index) ?: File(path).name)
                                    .setExtras(Bundle().apply { putString("openyt.kind", kinds.getOrNull(index) ?: MediaKind.VIDEO.name) })
                                    .build())
                                .build()
                        }, startIndex, position)
                    }
                } else if (connected.mediaItemCount == 0) {
                    val saved = container.playbackStore.loadQueue()
                    if (saved != null && saved.items.isNotEmpty()) {
                        val position = container.playbackStore.position(saved.current!!.path)?.resumeMillis ?: 0
                        connected.setMediaItems(saved.items.map {
                            MediaItem.Builder().setUri(Uri.fromFile(File(it.path)))
                                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title)
                                    .setExtras(Bundle().apply { putString("openyt.kind", it.kind.name) }).build())
                                .build()
                        }, saved.currentIndex, position)
                    }
                }
                title = connected.mediaMetadata.title?.toString().orEmpty()
                if (connected.mediaItemCount > 0) {
                    if (connected.playbackState == Player.STATE_IDLE) connected.prepare()
                    connected.play()
                } else playbackFailed = true
                updatePip()
            }
        }, ContextCompat.getMainExecutor(this))
        setContent {
            OpenYtTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        if (!inPip) {
                            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp), maxLines = 2)
                            Row {
                                if (supportsPip() && isVideo()) TextButton(onClick = ::enterPip, enabled = pipReady) { Text(strings.features[FeatureText.PIP]) }
                                TextButton(onClick = { finish() }) { Text(strings.closeAction) }
                            }
                            if (playbackFailed) Text(strings.features[FeatureText.PLAYBACK_FAILED], modifier = Modifier.padding(16.dp))
                        }
                        AndroidView(
                            factory = { context -> PlayerView(context).apply { setShowSubtitleButton(true) } },
                            update = { view -> view.player = controller; view.useController = !inPip },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    private fun supportsPip(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(controller?.isPlaying == true && isVideo())
        return builder.build()
    }

    private fun isVideo(): Boolean = controller?.mediaMetadata?.extras?.getString("openyt.kind") != MediaKind.AUDIO.name
    private fun updatePip() {
        pipReady = (controller?.mediaItemCount ?: 0) > 0 && isVideo() && !playbackFailed
        if (supportsPip()) setPictureInPictureParams(pipParams())
    }
    private fun enterPip() { if (supportsPip() && pipReady) enterPictureInPictureMode(pipParams()) }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (controller?.isPlaying == true && isVideo()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val player = controller ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
            KeyEvent.KEYCODE_DPAD_LEFT -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
            KeyEvent.KEYCODE_DPAD_RIGHT -> player.seekTo(player.currentPosition + 10_000)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATHS = "com.openyt.android.player.PATHS"
        const val EXTRA_TITLES = "com.openyt.android.player.TITLES"
        const val EXTRA_KINDS = "com.openyt.android.player.KINDS"
        const val EXTRA_START_INDEX = "com.openyt.android.player.START_INDEX"
    }
}
