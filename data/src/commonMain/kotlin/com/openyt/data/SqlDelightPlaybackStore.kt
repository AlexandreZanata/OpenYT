package com.openyt.data

import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackQueue
import com.openyt.core.application.PlaybackStore
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightPlaybackStore(database: OpenYtDatabase) : PlaybackStore {
    private val queries = database.downloadQueries
    override suspend fun position(path: String): PlaybackCheckpoint? = withContext(Dispatchers.Default) {
        queries.selectPlaybackPosition(path).executeAsOneOrNull()?.let {
            PlaybackCheckpoint(it.position_millis, it.duration_millis)
        }
    }
    override suspend fun savePosition(path: String, checkpoint: PlaybackCheckpoint) {
        withContext(Dispatchers.Default) {
            queries.savePlaybackPosition(path, checkpoint.positionMillis.coerceAtLeast(0), checkpoint.durationMillis.coerceAtLeast(0))
        }
    }
    override suspend fun loadQueue(): PlaybackQueue? = withContext(Dispatchers.Default) {
        queries.selectPlaybackQueue().executeAsOneOrNull()?.let { json ->
            runCatching { Json.decodeFromString<PlaybackQueue>(json) }.getOrNull()
        }
    }
    override suspend fun saveQueue(queue: PlaybackQueue) {
        withContext(Dispatchers.Default) { queries.savePlaybackQueue(Json.encodeToString(queue)) }
    }
}
