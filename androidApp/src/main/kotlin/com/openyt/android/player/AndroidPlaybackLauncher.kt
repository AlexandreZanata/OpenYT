package com.openyt.android.player

import android.content.Context
import android.content.Intent
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackLauncher

class AndroidPlaybackLauncher(private val context: Context) : PlaybackLauncher {
    override val canPlay: Boolean = true

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        if (items.isEmpty()) return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra(PlayerActivity.EXTRA_PATHS, ArrayList(items.map { it.path }))
            putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(items.map { it.title }))
            putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex.coerceIn(0, items.lastIndex))
        }
        context.startActivity(intent)
    }
}
