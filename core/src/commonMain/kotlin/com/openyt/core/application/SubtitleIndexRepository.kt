package com.openyt.core.application

import com.openyt.core.domain.IndexReconcileSummary
import com.openyt.core.domain.SubtitleIndexState
import com.openyt.core.domain.SubtitleTrackIndexRecord
import com.openyt.core.domain.TimedCue

interface SubtitleIndexRepository {
    suspend fun getTrackIndex(trackId: String): SubtitleTrackIndexRecord?

    suspend fun loadTrackIndexesForEntry(entryId: String): List<SubtitleTrackIndexRecord>

    suspend fun loadAllTrackIndexes(): List<SubtitleTrackIndexRecord>

    suspend fun saveTrackIndex(record: SubtitleTrackIndexRecord)

    suspend fun updateIndexState(trackId: String, state: SubtitleIndexState, errorMessage: String? = null)

    suspend fun beginRevision(
        trackId: String,
        entryId: String,
        language: String?,
        mediaRevisionToken: String,
        trackRevision: String,
    ): Int

    suspend fun persistCues(
        cues: List<TimedCue>,
        trackId: String,
        entryId: String,
        revision: Int,
    )

    suspend fun commitRevision(
        trackId: String,
        revision: Int,
        totalCues: Int,
        timebaseOffsetMs: Long,
    )

    suspend fun abortRevision(
        trackId: String,
        revision: Int,
        failureMessage: String? = null,
    )

    suspend fun deleteTrackIndex(trackId: String)

    suspend fun deleteTrackIndexesForEntry(entryId: String)

    suspend fun invalidateEntry(entryId: String, newState: SubtitleIndexState = SubtitleIndexState.STALE)

    suspend fun loadActiveCues(trackId: String): List<TimedCue>

    suspend fun countCuesAndFts(trackId: String, revision: Int): Pair<Int, Int>

    suspend fun cleanAbandonedRevisions(): IndexReconcileSummary
}
