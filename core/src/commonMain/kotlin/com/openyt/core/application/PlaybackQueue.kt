package com.openyt.core.application

/**
 * Pure, non-looping playback queue used for next/previous decisions and tests.
 */
data class PlaybackQueue(
    val items: List<PlayableMedia> = emptyList(),
    val currentIndex: Int = 0,
) {
    val current: PlayableMedia?
        get() = items.getOrNull(currentIndex)

    val hasNext: Boolean
        get() = currentIndex < items.lastIndex

    val hasPrevious: Boolean
        get() = currentIndex > 0

    fun next(): PlaybackQueue = if (hasNext) copy(currentIndex = currentIndex + 1) else this

    fun previous(): PlaybackQueue = if (hasPrevious) copy(currentIndex = currentIndex - 1) else this

    fun select(index: Int): PlaybackQueue =
        if (index in items.indices) copy(currentIndex = index) else this

    companion object {
        fun of(items: List<PlayableMedia>, startIndex: Int): PlaybackQueue =
            PlaybackQueue(
                items = items,
                currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            )
    }
}
