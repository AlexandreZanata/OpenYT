package com.openyt.core.application

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

@Serializable
data class PlaybackCheckpoint(
    val positionMillis: Long,
    val durationMillis: Long = 0,
    val entryId: String? = null,
) {
    val resumeMillis: Long
        get() = when {
            positionMillis < 0 -> 0
            durationMillis > 0 && positionMillis >= durationMillis - 3_000 -> 0
            else -> positionMillis
        }
}

interface PlaybackStore {
    suspend fun position(path: String): PlaybackCheckpoint?
    suspend fun positionForEntry(entryId: String): PlaybackCheckpoint? = null
    suspend fun position(path: String, entryId: String?): PlaybackCheckpoint? =
        if (entryId != null) positionForEntry(entryId) ?: position(path) else position(path)

    suspend fun savePosition(path: String, checkpoint: PlaybackCheckpoint) {
        savePosition(path, checkpoint, checkpoint.entryId)
    }
    suspend fun savePosition(path: String, checkpoint: PlaybackCheckpoint, entryId: String?)
    suspend fun loadQueue(): PlaybackQueue?
    suspend fun saveQueue(queue: PlaybackQueue)
}

class MemoryPlaybackStore : PlaybackStore {
    private val positions = MutableStateFlow<Map<String, PlaybackCheckpoint>>(emptyMap())
    private val entryPositions = MutableStateFlow<Map<String, PlaybackCheckpoint>>(emptyMap())
    private val queue = MutableStateFlow<PlaybackQueue?>(null)

    override suspend fun position(path: String): PlaybackCheckpoint? = positions.value[path]

    override suspend fun positionForEntry(entryId: String): PlaybackCheckpoint? = entryPositions.value[entryId]

    override suspend fun position(path: String, entryId: String?): PlaybackCheckpoint? =
        if (entryId != null) entryPositions.value[entryId] ?: positions.value[path] else positions.value[path]

    override suspend fun savePosition(path: String, checkpoint: PlaybackCheckpoint, entryId: String?) {
        val cp = if (entryId != null && checkpoint.entryId != entryId) checkpoint.copy(entryId = entryId) else checkpoint
        positions.update { it + (path to cp) }
        if (entryId != null) {
            entryPositions.update { it + (entryId to cp) }
        }
    }

    override suspend fun loadQueue(): PlaybackQueue? = queue.value
    override suspend fun saveQueue(queue: PlaybackQueue) { this.queue.value = queue }
}

/** Bounds a player window while preserving video aspect ratio. */
data class MiniPlayerSize(val width: Int, val height: Int)

fun miniPlayerSize(aspectRatio: Double = 16.0 / 9.0): MiniPlayerSize {
    val ratio = aspectRatio.takeIf { it.isFinite() && it > 0 }?.coerceIn(0.5, 2.4) ?: (16.0 / 9.0)
    return MiniPlayerSize(420, ((420 / ratio).toInt() + 84).coerceAtMost(720))
}
