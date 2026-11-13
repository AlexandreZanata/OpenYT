package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class SubtitleTrackIndexRecord(
    val trackId: String,
    val entryId: String,
    val language: String? = null,
    val mediaRevisionToken: String,
    val trackRevision: String,
    val activeRevision: Int = 0,
    val state: SubtitleIndexState = SubtitleIndexState.PENDING,
    val totalCues: Int = 0,
    val timebaseOffsetMs: Long = 0L,
    val errorMessage: String? = null,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
) {
    val isReady: Boolean get() = state == SubtitleIndexState.READY && totalCues > 0
    val hasUsableIndex: Boolean get() = activeRevision > 0 && totalCues > 0
}
