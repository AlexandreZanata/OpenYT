package com.openyt.android.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.openyt.android.OpenYtApplication
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackPositionSnapshot
import com.openyt.core.application.PlaybackQueue
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.clampSeekPosition
import com.openyt.core.domain.MediaKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private var sessionDelegate: AndroidPlaybackSessionBridge.ActiveSessionDelegate? = null
    private var writer: Job? = null
    @Volatile
    private var released = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val store get() = (application as OpenYtApplication).container.playbackStore

    override fun onCreate() {
        super.onCreate()
        writer = scope.launch { for (write in writes) runCatching { write() } }
        val player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val delegate = object : AndroidPlaybackSessionBridge.ActiveSessionDelegate {
            override fun captureBridgeState(): AndroidPlaybackSessionBridge.BridgeState =
                runOnMainSync(AndroidPlaybackSessionBridge.BridgeState()) { readBridgeState(player) }

            override fun seekTo(positionMillis: Long): Boolean = runOnMainSync(false) {
                if (player.currentMediaItem == null) return@runOnMainSync false
                val duration = player.duration.coerceAtLeast(0L)
                val clamped = clampSeekPosition(positionMillis, duration)
                player.seekTo(clamped)
                AndroidPlaybackSessionBridge.publishState(readBridgeState(player, clamped))
                true
            }

            override fun pause() {
                mainHandler.post {
                    player.pause()
                    AndroidPlaybackSessionBridge.publishState(readBridgeState(player))
                }
            }

            override fun play() {
                mainHandler.post {
                    player.play()
                    AndroidPlaybackSessionBridge.publishState(readBridgeState(player))
                }
            }

            override fun stop() {
                mainHandler.post {
                    player.stop()
                    player.clearMediaItems()
                    AndroidPlaybackSessionBridge.markIdle()
                }
            }
        }
        sessionDelegate = delegate
        AndroidPlaybackSessionBridge.attachService(delegate, readBridgeState(player))

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                AndroidPlaybackSessionBridge.nextGeneration()
                AndroidPlaybackSessionBridge.publishState(readBridgeState(player))
                val uri = mediaItem?.localConfiguration?.uri ?: return
                if (AndroidPlaybackSessionBridge.consumeExplicitStartUri(uri.toString())) return
                val entryId = mediaItem.mediaMetadata.extras?.getString("openyt.entryId")
                scope.launch {
                    val position = store.position(uri.path.orEmpty(), entryId)?.resumeMillis ?: 0
                    if (player.currentMediaItem?.localConfiguration?.uri == uri && position > 0 && player.currentPosition < 1_000) {
                        player.seekTo(position)
                    }
                    AndroidPlaybackSessionBridge.publishState(readBridgeState(player))
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                AndroidPlaybackSessionBridge.publishState(readBridgeState(player))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AndroidPlaybackSessionBridge.publishState(readBridgeState(player))
                saveSnapshot(player)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                AndroidPlaybackSessionBridge.publishState(readBridgeState(player))
            }

            override fun onPlayerError(error: PlaybackException) {
                AndroidPlaybackSessionBridge.markError()
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
        scope.launch {
            var lastSavedAt = 0L
            while (true) {
                val hasRepeat = AndroidPlaybackSessionBridge.hasActiveRepeatBounds()
                delay(if (hasRepeat) 100 else 1_000)
                AndroidPlaybackSessionBridge.publishState(readBridgeState(player))
                val now = SystemClock.elapsedRealtime()
                if (!hasRepeat || now - lastSavedAt >= 1_000L) {
                    lastSavedAt = now
                    saveSnapshot(player)
                }
            }
        }
    }

    private fun readBridgeState(
        player: Player,
        overridePositionMillis: Long? = null,
    ): AndroidPlaybackSessionBridge.BridgeState {
        val current = player.currentMediaItem
        val sessionState = when {
            player.playerError != null -> PlaybackSessionState.ERROR
            current == null -> PlaybackSessionState.IDLE
            player.playbackState == Player.STATE_ENDED -> PlaybackSessionState.COMPLETED
            player.playbackState == Player.STATE_BUFFERING ||
                player.playbackState == Player.STATE_IDLE -> PlaybackSessionState.PREPARING
            player.isPlaying -> PlaybackSessionState.PLAYING
            else -> PlaybackSessionState.PAUSED
        }
        val path = current?.localConfiguration?.uri?.path
        if (current == null || path == null) {
            return AndroidPlaybackSessionBridge.BridgeState(
                media = null,
                snapshot = null,
                state = sessionState,
            )
        }
        val kind = runCatching {
            MediaKind.valueOf(current.mediaMetadata.extras?.getString("openyt.kind").orEmpty())
        }.getOrDefault(MediaKind.VIDEO)
        val entryId = current.mediaMetadata.extras?.getString("openyt.entryId")?.takeIf { it.isNotBlank() }
        val title = current.mediaMetadata.title?.toString().orEmpty()
        val media = PlayableMedia(path = path, title = title, kind = kind, entryId = entryId)
        val duration = player.duration.coerceAtLeast(0L)
        val position = (overridePositionMillis ?: player.currentPosition).coerceAtLeast(0L)
        return AndroidPlaybackSessionBridge.BridgeState(
            media = media,
            snapshot = PlaybackPositionSnapshot(
                entryId = entryId,
                mediaPath = path,
                title = title,
                state = sessionState,
                capabilities = AndroidPlaybackSessionBridge.defaultCapabilities,
                positionMillis = position,
                durationMillis = duration,
                sessionGeneration = AndroidPlaybackSessionBridge.currentGeneration(),
            ),
            state = sessionState,
        )
    }

    private fun <T> runOnMainSync(fallback: T, block: () -> T): T {
        if (released) return fallback
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return if (released) fallback else block()
        }
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            result = if (released) Result.success(fallback) else runCatching(block)
            latch.countDown()
        }
        if (!posted) return fallback
        if (!latch.await(250, TimeUnit.MILLISECONDS)) return fallback
        return result?.getOrNull() ?: fallback
    }

    private fun saveSnapshot(player: Player) {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        val path = uri.path ?: return
        if (player.currentPosition <= 0 && player.duration <= 0) return
        val currentEntryId = player.currentMediaItem?.mediaMetadata?.extras?.getString("openyt.entryId")
        val position = PlaybackCheckpoint(
            player.currentPosition.coerceAtLeast(0),
            player.duration.coerceAtLeast(0),
            currentEntryId,
        )
        val items = (0 until player.mediaItemCount).mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            val itemPath = item.localConfiguration?.uri?.path ?: return@mapNotNull null
            val kind = runCatching {
                MediaKind.valueOf(item.mediaMetadata.extras?.getString("openyt.kind").orEmpty())
            }.getOrDefault(MediaKind.VIDEO)
            val itemEntryId = item.mediaMetadata.extras?.getString("openyt.entryId")
            PlayableMedia(itemPath, item.mediaMetadata.title?.toString().orEmpty(), kind, itemEntryId)
        }
        val queue = PlaybackQueue.of(items, player.currentMediaItemIndex)
        writes.trySend {
            store.savePosition(path, position, currentEntryId)
            store.saveQueue(queue)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        released = true
        sessionDelegate?.let { AndroidPlaybackSessionBridge.detachService(it) }
        sessionDelegate = null
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
