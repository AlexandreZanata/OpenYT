package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class FtsCapabilityReport(
    val sqliteVersion: String,
    val driverName: String,
    val osName: String,
    val architecture: String,
    val fts4Available: Boolean,
    val unicode61Available: Boolean,
    val accentFoldingWorking: Boolean,
    val crudWorking: Boolean,
    val corpusSize: Int,
    val warmupRuns: Int,
    val measuredRuns: Int,
    val p50Millis: Double,
    val p90Millis: Double,
    val p95Millis: Double,
    val maxMillis: Double,
    val averageMillis: Double,
    val budgetPassed: Boolean,
    val targetBudgetMillis: Double = 500.0,
    val timestampEpochMillis: Long,
)

@Serializable
data class FtsFunctionalVerification(
    val fts4Available: Boolean,
    val unicode61Available: Boolean,
    val accentFoldingWorking: Boolean,
    val crudWorking: Boolean,
    val notes: String = "",
) {
    val isSuccess: Boolean
        get() = fts4Available && unicode61Available && accentFoldingWorking && crudWorking
}
