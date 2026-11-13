package com.openyt.data

import com.openyt.core.application.SubtitleIndexRepository
import com.openyt.core.domain.IndexReconcileSummary
import com.openyt.core.domain.SubtitleIndexState
import com.openyt.core.domain.SubtitleTrackIndexRecord
import com.openyt.core.domain.TimedCue
import com.openyt.data.db.OpenYtDatabase
import com.openyt.data.db.Subtitle_cue
import com.openyt.data.db.Subtitle_track_index
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightSubtitleIndexRepository(
    private val database: OpenYtDatabase,
) : SubtitleIndexRepository {

    override suspend fun getTrackIndex(trackId: String): SubtitleTrackIndexRecord? = withContext(Dispatchers.Default) {
        database.downloadQueries.selectSubtitleTrackIndex(trackId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun loadTrackIndexesForEntry(entryId: String): List<SubtitleTrackIndexRecord> = withContext(Dispatchers.Default) {
        database.downloadQueries.selectSubtitleTrackIndexesForEntry(entryId).executeAsList().map { it.toDomain() }
    }

    override suspend fun loadAllTrackIndexes(): List<SubtitleTrackIndexRecord> = withContext(Dispatchers.Default) {
        database.downloadQueries.selectAllSubtitleTrackIndexes().executeAsList().map { it.toDomain() }
    }

    override suspend fun saveTrackIndex(record: SubtitleTrackIndexRecord): Unit = withContext(Dispatchers.Default) {
        database.downloadQueries.upsertSubtitleTrackIndex(
            track_id = record.trackId,
            entry_id = record.entryId,
            language = record.language,
            media_revision_token = record.mediaRevisionToken,
            track_revision = record.trackRevision,
            active_revision = record.activeRevision.toLong(),
            state = record.state.name,
            total_cues = record.totalCues.toLong(),
            timebase_offset_ms = record.timebaseOffsetMs,
            error_message = record.errorMessage,
            created_at = if (record.createdAtEpochMillis > 0L) record.createdAtEpochMillis else currentTimeMillis(),
            updated_at = if (record.updatedAtEpochMillis > 0L) record.updatedAtEpochMillis else currentTimeMillis(),
        )
    }

    override suspend fun updateIndexState(
        trackId: String,
        state: SubtitleIndexState,
        errorMessage: String?,
    ): Unit = withContext(Dispatchers.Default) {
        database.downloadQueries.updateSubtitleTrackIndexState(
            state = state.name,
            error_message = errorMessage,
            updated_at = currentTimeMillis(),
            track_id = trackId,
        )
    }

    override suspend fun beginRevision(
        trackId: String,
        entryId: String,
        language: String?,
        mediaRevisionToken: String,
        trackRevision: String,
    ): Int = withContext(Dispatchers.Default) {
        database.downloadQueries.transactionWithResult {
            val existing = database.downloadQueries.selectSubtitleTrackIndex(trackId).executeAsOneOrNull()
            val currentActiveRevision = existing?.active_revision?.toInt() ?: 0
            val nextRevision = currentActiveRevision + 1
            val now = currentTimeMillis()

            // Purge any stale staging cues/fts rows that might have been left from a previously interrupted attempt for nextRevision
            database.downloadQueries.deleteCuesForTrackAndRevision(trackId, nextRevision.toLong())
            database.downloadQueries.deleteFtsCuesForTrackAndRevision(trackId, nextRevision.toString())

            database.downloadQueries.upsertSubtitleTrackIndex(
                track_id = trackId,
                entry_id = entryId,
                language = language ?: existing?.language,
                media_revision_token = mediaRevisionToken,
                track_revision = trackRevision,
                active_revision = currentActiveRevision.toLong(), // Keep old usable revision active!
                state = SubtitleIndexState.INDEXING.name,
                total_cues = existing?.total_cues ?: 0L,
                timebase_offset_ms = existing?.timebase_offset_ms ?: 0L,
                error_message = null,
                created_at = existing?.created_at ?: now,
                updated_at = now,
            )

            nextRevision
        }
    }

    override suspend fun persistCues(
        cues: List<TimedCue>,
        trackId: String,
        entryId: String,
        revision: Int,
    ): Unit = withContext(Dispatchers.Default) {
        val now = currentTimeMillis()
        database.downloadQueries.transaction {
            for (cue in cues) {
                val rowId = "$trackId:$revision:${cue.cueId}"
                database.downloadQueries.insertSubtitleCue(
                    id = rowId,
                    track_id = trackId,
                    entry_id = entryId,
                    revision = revision.toLong(),
                    cue_id = cue.cueId,
                    start_ms = cue.startMs,
                    end_ms = cue.endMs,
                    display_text = cue.displayText,
                    plain_text = cue.plainText,
                    speaker = cue.speaker,
                    original_index = cue.originalIndex.toLong(),
                    created_at = now,
                )
                database.downloadQueries.insertSubtitleCueFts(
                    cue_id = cue.cueId,
                    track_id = trackId,
                    entry_id = entryId,
                    revision = revision.toString(),
                    start_ms = cue.startMs.toString(),
                    end_ms = cue.endMs.toString(),
                    plain_text = cue.plainText,
                )
            }
        }
    }

    override suspend fun commitRevision(
        trackId: String,
        revision: Int,
        totalCues: Int,
        timebaseOffsetMs: Long,
    ): Unit = withContext(Dispatchers.Default) {
        val now = currentTimeMillis()
        database.downloadQueries.transaction {
            // 1. Atomically switch active revision and mark READY
            database.downloadQueries.updateSubtitleTrackIndexCommittedRevision(
                active_revision = revision.toLong(),
                state = SubtitleIndexState.READY.name,
                total_cues = totalCues.toLong(),
                timebase_offset_ms = timebaseOffsetMs,
                updated_at = now,
                track_id = trackId,
            )
            // 2. Delete cues and FTS rows belonging to older revisions of this track
            database.downloadQueries.deleteCuesForTrackExceptRevision(trackId, revision.toLong())
            database.downloadQueries.deleteFtsCuesForTrackExceptRevision(trackId, revision.toString())
        }
    }

    override suspend fun abortRevision(
        trackId: String,
        revision: Int,
        failureMessage: String?,
    ): Unit = withContext(Dispatchers.Default) {
        val now = currentTimeMillis()
        database.downloadQueries.transaction {
            database.downloadQueries.deleteCuesForTrackAndRevision(trackId, revision.toLong())
            database.downloadQueries.deleteFtsCuesForTrackAndRevision(trackId, revision.toString())

            val existing = database.downloadQueries.selectSubtitleTrackIndex(trackId).executeAsOneOrNull()
            if (existing != null) {
                val newState = if (existing.active_revision > 0L) {
                    if (failureMessage != null) SubtitleIndexState.STALE else SubtitleIndexState.READY
                } else {
                    if (failureMessage != null) SubtitleIndexState.FAILED else SubtitleIndexState.PENDING
                }
                database.downloadQueries.updateSubtitleTrackIndexState(
                    state = newState.name,
                    error_message = failureMessage,
                    updated_at = now,
                    track_id = trackId,
                )
            }
        }
    }

    override suspend fun deleteTrackIndex(trackId: String): Unit = withContext(Dispatchers.Default) {
        database.downloadQueries.transaction {
            database.downloadQueries.deleteCuesForTrack(trackId)
            database.downloadQueries.deleteFtsCuesForTrack(trackId)
            database.downloadQueries.deleteSubtitleTrackIndex(trackId)
        }
    }

    override suspend fun deleteTrackIndexesForEntry(entryId: String): Unit = withContext(Dispatchers.Default) {
        database.downloadQueries.transaction {
            database.downloadQueries.deleteCuesForEntry(entryId)
            database.downloadQueries.deleteFtsCuesForEntry(entryId)
            database.downloadQueries.deleteSubtitleTrackIndexesForEntry(entryId)
        }
    }

    override suspend fun invalidateEntry(
        entryId: String,
        newState: SubtitleIndexState,
    ): Unit = withContext(Dispatchers.Default) {
        database.downloadQueries.invalidateSubtitleIndexesForEntry(
            state = newState.name,
            updated_at = currentTimeMillis(),
            entry_id = entryId,
        )
    }

    override suspend fun loadActiveCues(trackId: String): List<TimedCue> = withContext(Dispatchers.Default) {
        database.downloadQueries.selectActiveCuesForTrack(trackId).executeAsList().map { it.toDomain() }
    }

    override suspend fun countCuesAndFts(trackId: String, revision: Int): Pair<Int, Int> = withContext(Dispatchers.Default) {
        val cueCount = database.downloadQueries.countCuesForTrackAndRevision(trackId, revision.toLong()).executeAsOne().toInt()
        val ftsCount = database.downloadQueries.countFtsCuesForTrackAndRevision(trackId, revision.toString()).executeAsOne().toInt()
        Pair(cueCount, ftsCount)
    }

    override suspend fun cleanAbandonedRevisions(): IndexReconcileSummary = withContext(Dispatchers.Default) {
        database.downloadQueries.transactionWithResult {
            var interruptedRecovered = 0
            var cleanedCues = 0
            var cleanedFts = 0

            // 1. Recover interrupted INDEXING tracks
            val indexingTracks = database.downloadQueries.selectActiveIndexingTrackIndexes().executeAsList()
            val now = currentTimeMillis()
            for (track in indexingTracks) {
                val newState = if (track.active_revision > 0L) SubtitleIndexState.STALE else SubtitleIndexState.FAILED
                database.downloadQueries.updateSubtitleTrackIndexState(
                    state = newState.name,
                    error_message = "Indexing interrupted by process restart or crash",
                    updated_at = now,
                    track_id = track.track_id,
                )
                interruptedRecovered++
            }

            // 2. Clean cues and FTS rows that do not match the committed active_revision of their track
            val allTracks = database.downloadQueries.selectAllSubtitleTrackIndexes().executeAsList()
            for (track in allTracks) {
                val cuesToDelete = database.downloadQueries.countCuesForTrackExceptRevision(track.track_id, track.active_revision).executeAsOne().toInt()
                val ftsToDelete = database.downloadQueries.countFtsCuesForTrackExceptRevision(track.track_id, track.active_revision.toString()).executeAsOne().toInt()

                if (cuesToDelete > 0) {
                    database.downloadQueries.deleteCuesForTrackExceptRevision(track.track_id, track.active_revision)
                    cleanedCues += cuesToDelete
                }
                if (ftsToDelete > 0) {
                    database.downloadQueries.deleteFtsCuesForTrackExceptRevision(track.track_id, track.active_revision.toString())
                    cleanedFts += ftsToDelete
                }
            }

            // 3. Clean orphaned cues and FTS for tracks that no longer exist
            val orphanedCues = database.downloadQueries.countOrphanedSubtitleCues().executeAsOne().toInt()
            val orphanedFts = database.downloadQueries.countOrphanedSubtitleFtsCues().executeAsOne().toInt()

            if (orphanedCues > 0) {
                database.downloadQueries.deleteOrphanedSubtitleCues()
                cleanedCues += orphanedCues
            }
            if (orphanedFts > 0) {
                database.downloadQueries.deleteOrphanedSubtitleFtsCues()
                cleanedFts += orphanedFts
            }

            IndexReconcileSummary(
                reconciledTracks = allTracks.size,
                cleanedOrphanedCues = cleanedCues,
                cleanedOrphanedFtsRows = cleanedFts,
                interruptedJobsRecovered = interruptedRecovered,
            )
        }
    }

    private fun currentTimeMillis(): Long =
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1700000000000L

    private fun Subtitle_track_index.toDomain(): SubtitleTrackIndexRecord =
        SubtitleTrackIndexRecord(
            trackId = track_id,
            entryId = entry_id,
            language = language,
            mediaRevisionToken = media_revision_token,
            trackRevision = track_revision,
            activeRevision = active_revision.toInt(),
            state = runCatching { SubtitleIndexState.valueOf(state) }.getOrDefault(SubtitleIndexState.PENDING),
            totalCues = total_cues.toInt(),
            timebaseOffsetMs = timebase_offset_ms,
            errorMessage = error_message,
            createdAtEpochMillis = created_at,
            updatedAtEpochMillis = updated_at,
        )

    private fun Subtitle_cue.toDomain(): TimedCue =
        TimedCue(
            cueId = cue_id,
            trackId = track_id,
            entryId = entry_id,
            startMs = start_ms,
            endMs = end_ms,
            displayText = display_text,
            plainText = plain_text,
            originalIndex = original_index.toInt(),
            speaker = speaker,
        )
}
