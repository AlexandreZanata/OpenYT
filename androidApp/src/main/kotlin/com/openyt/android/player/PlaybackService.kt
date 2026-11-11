package com.openyt.android.player

import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.openyt.android.OpenYtApplication
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackQueue
import com.openyt.core.domain.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var writer: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val store get() = (application as OpenYtApplication).container.playbackStore

    override fun onCreate() {
        super.onCreate()
        writer = scope.launch { for (write in writes) runCatching { write() } }
        val player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri ?: return
                scope.launch {
                    val position = store.position(uri.path.orEmpty())?.resumeMillis ?: 0
                    if (player.currentMediaItem?.localConfiguration?.uri == uri && position > 0 && player.currentPosition < 1_000) {
                        player.seekTo(position)
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { saveSnapshot(player) }
        })
        mediaSession = MediaSession.Builder(this, player).build()
        scope.launch {
            while (true) {
                delay(5_000)
                saveSnapshot(player)
            }
        }
    }

    private fun saveSnapshot(player: Player) {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        val path = uri.path ?: return
        if (player.currentPosition <= 0 && player.duration <= 0) return
        val position = PlaybackCheckpoint(player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0))
        val items = (0 until player.mediaItemCount).mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            val itemPath = item.localConfiguration?.uri?.path ?: return@mapNotNull null
            val kind = runCatching {
                MediaKind.valueOf(item.mediaMetadata.extras?.getString("openyt.kind").orEmpty())
            }.getOrDefault(MediaKind.VIDEO)
            PlayableMedia(itemPath, item.mediaMetadata.title?.toString().orEmpty(), kind)
        }
        val queue = PlaybackQueue.of(items, player.currentMediaItemIndex)
        writes.trySend {
            store.savePosition(path, position)
            store.saveQueue(queue)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            saveSnapshot(player)
            player.release()
            release()
        }
        mediaSession = null
        writes.close()
        scope.launch {
            writer?.join()
            scope.cancel()
        }
        super.onDestroy()
    }
}
