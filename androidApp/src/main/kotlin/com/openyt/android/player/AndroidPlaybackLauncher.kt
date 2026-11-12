package com.openyt.android.player

import android.content.Context
import android.content.Intent
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCapabilities
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.application.PlaybackPositionSnapshot
import com.openyt.core.application.PlaybackSessionControl
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.clampSeekPosition
import java.io.File

class AndroidPlaybackLauncher(
    private val context: Context,
    private val store: com.openyt.core.application.PlaybackStore? = null,
) : PlaybackLauncher, PlaybackSessionControl {
    override val canPlay: Boolean = true

    override val loadedMedia: PlayableMedia?
        get() = AndroidPlaybackSessionBridge.currentBridgeState().media

    override val state: PlaybackSessionState
        get() = AndroidPlaybackSessionBridge.currentBridgeState().state

    override val capabilities: PlaybackCapabilities
        get() = AndroidPlaybackSessionBridge.defaultCapabilities

    override val currentPositionMillis: Long
        get() = AndroidPlaybackSessionBridge.currentBridgeState().snapshot?.positionMillis ?: 0L

    override val durationMillis: Long
        get() = AndroidPlaybackSessionBridge.currentBridgeState().snapshot?.durationMillis ?: 0L

    override val currentSessionGeneration: Long
        get() = AndroidPlaybackSessionBridge.currentGeneration()

    override fun captureSnapshot(): PlaybackPositionSnapshot? =
        AndroidPlaybackSessionBridge.currentBridgeState().snapshot

    override suspend fun resumeLastSession(): Boolean {
        val queue = store?.loadQueue() ?: return false
        val items = queue.items.filter { File(it.path).isFile }
        if (items.isEmpty()) return false
        val index = items.indexOfFirst { it.path == queue.current?.path }.coerceAtLeast(0)
        play(items, index)
        return true
    }

    override suspend fun openAt(
        items: List<PlayableMedia>,
        startIndex: Int,
        startPositionMillis: Long?,
    ): Boolean {
        val item = items.getOrNull(startIndex) ?: return false
        val reachable = item.path.startsWith("content://") || File(item.path).isFile
        if (!reachable) {
            AndroidPlaybackSessionBridge.markError()
            return false
        }
        val clamped = startPositionMillis?.let { clampSeekPosition(it, 0L) }
        AndroidPlaybackSessionBridge.markPreparing(item, clamped)
        playAt(items, startIndex, clamped ?: -1L)
        return true
    }

    override suspend fun seekTo(positionMillis: Long): Boolean =
        AndroidPlaybackSessionBridge.seekTo(positionMillis)

    override suspend fun pause() {
        AndroidPlaybackSessionBridge.pause()
    }

    override suspend fun play() {
        AndroidPlaybackSessionBridge.play()
    }

    override suspend fun stop() {
        AndroidPlaybackSessionBridge.stop()
    }

    override fun close() {
        AndroidPlaybackSessionBridge.stop()
    }

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        playAt(items, startIndex, -1L)
    }

    override fun playAt(items: List<PlayableMedia>, startIndex: Int, startPositionMillis: Long) {
        if (items.isEmpty()) return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra(PlayerActivity.EXTRA_PATHS, ArrayList(items.map { it.path }))
            putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(items.map { it.title }))
            putStringArrayListExtra(PlayerActivity.EXTRA_KINDS, ArrayList(items.map { it.kind.name }))
            putStringArrayListExtra(PlayerActivity.EXTRA_ENTRY_IDS, ArrayList(items.map { it.entryId ?: "" }))
            putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex.coerceIn(0, items.lastIndex))
            if (startPositionMillis >= 0L) {
                putExtra(PlayerActivity.EXTRA_START_POSITION_MILLIS, startPositionMillis)
            }
        }
        context.startActivity(intent)
    }
}
