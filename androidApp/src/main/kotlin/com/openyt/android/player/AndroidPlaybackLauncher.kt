package com.openyt.android.player

import android.content.Context
import android.content.Intent
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackLauncher

class AndroidPlaybackLauncher(private val context: Context, private val store: com.openyt.core.application.PlaybackStore? = null) : PlaybackLauncher {
    override val canPlay: Boolean = true

    override suspend fun resumeLastSession(): Boolean {
        val queue = store?.loadQueue() ?: return false
        val items = queue.items.filter { java.io.File(it.path).isFile }
        if (items.isEmpty()) return false
        play(items, items.indexOfFirst { it.path == queue.current?.path }.coerceAtLeast(0))
        return true
    }

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        if (items.isEmpty()) return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra(PlayerActivity.EXTRA_PATHS, ArrayList(items.map { it.path }))
            putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(items.map { it.title }))
            putStringArrayListExtra(PlayerActivity.EXTRA_KINDS, ArrayList(items.map { it.kind.name }))
            putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex.coerceIn(0, items.lastIndex))
        }
        context.startActivity(intent)
    }
}
