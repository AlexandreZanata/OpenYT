package com.openyt.core.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtsCapabilityReportTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun reportSerializationRoundTrip() {
        val report = FtsCapabilityReport(
            sqliteVersion = "3.53.4",
            driverName = "JdbcSqliteDriver (Xerial SQLite JDBC)",
            osName = "Linux",
            architecture = "amd64",
            fts4Available = true,
            unicode61Available = true,
            accentFoldingWorking = true,
            crudWorking = true,
            corpusSize = 100_000,
            warmupRuns = 5,
            measuredRuns = 30,
            p50Millis = 0.35,
            p90Millis = 0.85,
            p95Millis = 0.89,
            maxMillis = 0.95,
            averageMillis = 0.42,
            budgetPassed = true,
            targetBudgetMillis = 500.0,
            timestampEpochMillis = 1731427200000L,
        )

        val serialized = json.encodeToString(report)
        val deserialized = json.decodeFromString<FtsCapabilityReport>(serialized)

        assertEquals(report, deserialized)
        assertTrue(deserialized.budgetPassed)
        assertTrue(deserialized.p95Millis <= deserialized.targetBudgetMillis)
    }

    @Test
    fun functionalVerificationSuccessCondition() {
        val success = FtsFunctionalVerification(
            fts4Available = true,
            unicode61Available = true,
            accentFoldingWorking = true,
            crudWorking = true,
            notes = "All capabilities passed",
        )
        assertTrue(success.isSuccess)

        val failedAccent = success.copy(accentFoldingWorking = false)
        assertFalse(failedAccent.isSuccess)

        val failedCrud = success.copy(crudWorking = false)
        assertFalse(failedCrud.isSuccess)

        val failedFts = success.copy(fts4Available = false)
        assertFalse(failedFts.isSuccess)

        val failedTokenizer = success.copy(unicode61Available = false)
        assertFalse(failedTokenizer.isSuccess)
    }
}
