package com.openyt.core.application

import com.openyt.core.domain.AssetState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset

enum class MediaHealthStatus {
    AVAILABLE,
    MISSING,
    PERMISSION_LOST,
    CHANGED_CONTENT,
    UNKNOWN,
}

enum class MediaProbeOutcomeKind {
    AVAILABLE,
    MISSING,
    PERMISSION_DENIED,
    IO_ERROR,
}

data class MediaProbeHealth(
    val outcome: MediaProbeOutcomeKind,
    val sizeBytes: Long? = null,
    val lastModifiedEpochMillis: Long? = null,
    val contentSha256: String? = null,
    val safeDiagnostic: String? = null,
)

/**
 * Read-only health inspection port distinguishing missing media, lost OS/SAF
 * permissions, content mutations, and healthy files without touching disk or DB.
 */
interface MediaHealthInspector {
    suspend fun inspect(
        locator: String,
        expectedRevision: MediaRevision?,
        computeHashIfUncertain: Boolean = false,
    ): MediaProbeHealth
}

data class LibraryEntryHealth(
    val entry: LibraryEntry,
    val status: MediaHealthStatus,
    val observedRevision: MediaRevision? = null,
    val staleAssets: List<OfflineAsset> = emptyList(),
    val missingRequiredAssets: List<OfflineAsset> = emptyList(),
    val safeReason: String? = null,
) {
    val needsRecovery: Boolean
        get() = status != MediaHealthStatus.AVAILABLE
}

data class LibraryHealthSummary(
    val totalEntries: Int,
    val availableCount: Int,
    val missingCount: Int,
    val permissionLostCount: Int,
    val changedContentCount: Int,
    val unknownCount: Int,
    val entriesWithStaleAssets: Int,
) {
    val unhealthyCount: Int
        get() = missingCount + permissionLostCount + changedContentCount + unknownCount
}

data class LibraryHealthReport(
    val generatedAtEpochMillis: Long,
    val entries: List<LibraryEntryHealth>,
    val summary: LibraryHealthSummary,
)

/**
 * Computes a strictly read-only health report over library entries and offline assets.
 * Never deletes files or mutates SQLite state.
 */
class LibraryHealthAnalyzer(
    private val inspector: MediaHealthInspector,
    private val nowProvider: () -> Long = {
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1_700_000_000_000L
    },
) {
    suspend fun analyze(
        entries: List<LibraryEntry>,
        assets: List<OfflineAsset> = emptyList(),
        verifyHashesWhenUncertain: Boolean = false,
    ): LibraryHealthReport {
        val assetsByEntry = assets.groupBy { it.entryId }
        val healthRows = entries.map { entry ->
            val entryAssets = assetsByEntry[entry.id].orEmpty()
            val staleAssets = entryAssets.filter { it.state == AssetState.STALE }
            val missingRequired = entryAssets.filter { it.isRequired && it.state != AssetState.VERIFIED }

            val probe = try {
                inspector.inspect(
                    locator = entry.filePath,
                    expectedRevision = entry.mediaRevision,
                    computeHashIfUncertain = verifyHashesWhenUncertain,
                )
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                MediaProbeHealth(
                    outcome = MediaProbeOutcomeKind.IO_ERROR,
                    safeDiagnostic = sanitizeHealthDiagnostic(error.message),
                )
            }

            val observedRev = if (probe.outcome == MediaProbeOutcomeKind.AVAILABLE && probe.sizeBytes != null) {
                MediaRevision(
                    sizeBytes = probe.sizeBytes,
                    lastModifiedEpochMillis = probe.lastModifiedEpochMillis ?: 0L,
                    contentSha256 = probe.contentSha256,
                )
            } else {
                null
            }

            val (status, reason) = classifyEntry(
                entry = entry,
                probe = probe,
            )

            LibraryEntryHealth(
                entry = entry,
                status = status,
                observedRevision = observedRev,
                staleAssets = staleAssets,
                missingRequiredAssets = missingRequired,
                safeReason = reason,
            )
        }

        val summary = LibraryHealthSummary(
            totalEntries = healthRows.size,
            availableCount = healthRows.count { it.status == MediaHealthStatus.AVAILABLE },
            missingCount = healthRows.count { it.status == MediaHealthStatus.MISSING },
            permissionLostCount = healthRows.count { it.status == MediaHealthStatus.PERMISSION_LOST },
            changedContentCount = healthRows.count { it.status == MediaHealthStatus.CHANGED_CONTENT },
            unknownCount = healthRows.count { it.status == MediaHealthStatus.UNKNOWN },
            entriesWithStaleAssets = healthRows.count { it.staleAssets.isNotEmpty() },
        )
        return LibraryHealthReport(
            generatedAtEpochMillis = nowProvider(),
            entries = healthRows,
            summary = summary,
        )
    }

    private fun classifyEntry(
        entry: LibraryEntry,
        probe: MediaProbeHealth,
    ): Pair<MediaHealthStatus, String?> = when (probe.outcome) {
        MediaProbeOutcomeKind.PERMISSION_DENIED ->
            MediaHealthStatus.PERMISSION_LOST to (probe.safeDiagnostic ?: "PERMISSION_DENIED")

        MediaProbeOutcomeKind.MISSING ->
            MediaHealthStatus.MISSING to (probe.safeDiagnostic ?: "MISSING")

        MediaProbeOutcomeKind.IO_ERROR ->
            MediaHealthStatus.UNKNOWN to (probe.safeDiagnostic ?: "IO_ERROR")

        MediaProbeOutcomeKind.AVAILABLE -> {
            val expectedRev = entry.mediaRevision
            val actualSize = probe.sizeBytes
            when {
                expectedRev != null && actualSize != null && actualSize != expectedRev.sizeBytes ->
                    MediaHealthStatus.CHANGED_CONTENT to "SIZE_MISMATCH"

                expectedRev?.contentSha256 != null &&
                    probe.contentSha256 != null &&
                    !probe.contentSha256.equals(expectedRev.contentSha256, ignoreCase = true) ->
                    MediaHealthStatus.CHANGED_CONTENT to "SHA256_MISMATCH"

                expectedRev == null &&
                    entry.sizeBytes != null &&
                    actualSize != null &&
                    actualSize != entry.sizeBytes ->
                    MediaHealthStatus.CHANGED_CONTENT to "SIZE_MISMATCH"

                else ->
                    MediaHealthStatus.AVAILABLE to null
            }
        }
    }

    companion object {
        fun sanitizeHealthDiagnostic(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return raw
                .replace(Regex("[A-Za-z]:\\\\[^\\s]+"), "<path>")
                .replace(Regex("/[^\\s]+"), "<path>")
                .replace(Regex("content://[^\\s]+"), "<uri>")
                .take(120)
                .trim()
                .ifBlank { null }
        }
    }
}
