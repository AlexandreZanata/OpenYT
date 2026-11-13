package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class IndexReconcileSummary(
    val reconciledTracks: Int = 0,
    val cleanedOrphanedCues: Int = 0,
    val cleanedOrphanedFtsRows: Int = 0,
    val interruptedJobsRecovered: Int = 0,
)
