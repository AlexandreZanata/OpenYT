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
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun position(path: String): PlaybackCheckpoint? = withContext(Dispatchers.Default) {
        queries.selectPlaybackPosition(path).executeAsOneOrNull()?.let {
            PlaybackCheckpoint(it.position_millis, it.duration_millis, it.entry_id)
        }
    }

    override suspend fun positionForEntry(entryId: String): PlaybackCheckpoint? = withContext(Dispatchers.Default) {
        queries.selectPlaybackPositionByEntryId(entryId).executeAsOneOrNull()?.let {
            PlaybackCheckpoint(it.position_millis, it.duration_millis, it.entry_id)
        }
    }

    override suspend fun position(path: String, entryId: String?): PlaybackCheckpoint? = withContext(Dispatchers.Default) {
        if (entryId != null) {
            val byEntry = queries.selectPlaybackPositionByEntryId(entryId).executeAsOneOrNull()
            if (byEntry != null) {
                return@withContext PlaybackCheckpoint(byEntry.position_millis, byEntry.duration_millis, byEntry.entry_id)
            }
            val byPath = queries.selectPlaybackPosition(path).executeAsOneOrNull()
            if (byPath != null) {
                if (byPath.entry_id == null) {
                    queries.updatePlaybackPositionEntryId(entry_id = entryId, path = path)
                }
                return@withContext PlaybackCheckpoint(byPath.position_millis, byPath.duration_millis, entryId)
            }
            null
        } else {
            queries.selectPlaybackPosition(path).executeAsOneOrNull()?.let {
                PlaybackCheckpoint(it.position_millis, it.duration_millis, it.entry_id)
            }
        }
    }

    override suspend fun savePosition(path: String, checkpoint: PlaybackCheckpoint, entryId: String?) {
        withContext(Dispatchers.Default) {
            val resolvedEntryId = entryId ?: checkpoint.entryId
            if (resolvedEntryId != null) {
                val existing = queries.selectPlaybackPositionByEntryId(resolvedEntryId).executeAsOneOrNull()
                if (existing != null && existing.path != path) {
                    queries.deletePlaybackPositionByEntryId(resolvedEntryId)
                }
            }
            queries.savePlaybackPosition(
                path = path,
                position_millis = checkpoint.positionMillis.coerceAtLeast(0),
                duration_millis = checkpoint.durationMillis.coerceAtLeast(0),
                entry_id = resolvedEntryId,
            )
        }
    }

    override suspend fun loadQueue(): PlaybackQueue? = withContext(Dispatchers.Default) {
        queries.selectPlaybackQueue().executeAsOneOrNull()?.let { rawJson ->
            runCatching { json.decodeFromString<PlaybackQueue>(rawJson) }.getOrNull()
        }
    }

    override suspend fun saveQueue(queue: PlaybackQueue) {
        withContext(Dispatchers.Default) {
            queries.savePlaybackQueue(json.encodeToString(queue))
        }
    }
}
