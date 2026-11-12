package com.openyt.android.player

import android.content.Context
import android.content.Intent
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackLauncher

class AndroidPlaybackLauncher(
    private val context: Context,
    private val store: com.openyt.core.application.PlaybackStore? = null,
) : PlaybackLauncher {
    override val canPlay: Boolean = true

    override suspend fun resumeLastSession(): Boolean {
        val queue = store?.loadQueue() ?: return false
        val items = queue.items.filter { java.io.File(it.path).isFile }
        if (items.isEmpty()) return false
        val index = items.indexOfFirst { it.path == queue.current?.path }.coerceAtLeast(0)
        play(items, index)
        return true
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
