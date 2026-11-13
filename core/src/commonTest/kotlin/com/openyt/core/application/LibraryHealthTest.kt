package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryHealthTest {

    @Test
    fun classifiesAvailableMissingPermissionLostChangedContentAndStaleAssetsWithoutMutating() = runBlocking {
        val revMatching = MediaRevision(1000L, 50L, "sha-match")
        val revChanged = MediaRevision(2000L, 60L, "sha-orig")

        val entries = listOf(
            LibraryEntry("e-ok", "Healthy", MediaKind.VIDEO, "/media/ok.mp4", sizeBytes = 1000L, mediaRevision = revMatching),
            LibraryEntry("e-miss", "Missing", MediaKind.VIDEO, "/media/miss.mp4", sizeBytes = 1000L),
            LibraryEntry("e-perm", "Revoked", MediaKind.AUDIO, "content://saf/doc/42", sizeBytes = 500L),
            LibraryEntry("e-diff", "Mutated", MediaKind.VIDEO, "/media/diff.mp4", sizeBytes = 2000L, mediaRevision = revChanged),
            LibraryEntry("e-err", "Error", MediaKind.VIDEO, "/media/secret/user/token/err.mp4"),
        )
        val assets = listOf(
            OfflineAsset(
                id = "a1",
                entryId = "e-ok",
                kind = AssetKind.SUBTITLE,
                relativeLocator = "e-ok/sub.srt",
                byteCount = 120L,
                sha256 = "s1",
                state = AssetState.STALE,
                mediaRevision = revMatching,
            ),
        )
        val probeResults = mapOf(
            "/media/ok.mp4" to MediaProbeHealth(MediaProbeOutcomeKind.AVAILABLE, 1000L, 50L, "sha-match"),
            "/media/miss.mp4" to MediaProbeHealth(MediaProbeOutcomeKind.MISSING, safeDiagnostic = "NOT_FOUND"),
            "content://saf/doc/42" to MediaProbeHealth(MediaProbeOutcomeKind.PERMISSION_DENIED, safeDiagnostic = "SAF_PERMISSION_REVOKED"),
            "/media/diff.mp4" to MediaProbeHealth(MediaProbeOutcomeKind.AVAILABLE, 2000L, 70L, "sha-different"),
        )
        val inspector = object : MediaHealthInspector {
            override suspend fun inspect(
                locator: String,
                expectedRevision: MediaRevision?,
                computeHashIfUncertain: Boolean,
            ): MediaProbeHealth = probeResults[locator]
                ?: error("IO fault at /home/alice/private/secret/file.mp4")
        }

        val analyzer = LibraryHealthAnalyzer(inspector, nowProvider = { 999_000L })
        val report = analyzer.analyze(entries, assets, verifyHashesWhenUncertain = true)

        assertEquals(5, report.summary.totalEntries)
        assertEquals(1, report.summary.availableCount)
        assertEquals(1, report.summary.missingCount)
        assertEquals(1, report.summary.permissionLostCount)
        assertEquals(1, report.summary.changedContentCount)
        assertEquals(1, report.summary.unknownCount)
        assertEquals(1, report.summary.entriesWithStaleAssets)

        val byId = report.entries.associateBy { it.entry.id }
        assertEquals(MediaHealthStatus.AVAILABLE, byId.getValue("e-ok").status)
        assertEquals(1, byId.getValue("e-ok").staleAssets.size)
        assertEquals(MediaHealthStatus.MISSING, byId.getValue("e-miss").status)
        assertEquals(MediaHealthStatus.PERMISSION_LOST, byId.getValue("e-perm").status)
        assertEquals(MediaHealthStatus.CHANGED_CONTENT, byId.getValue("e-diff").status)
        assertEquals("SHA256_MISMATCH", byId.getValue("e-diff").safeReason)
        assertEquals(MediaHealthStatus.UNKNOWN, byId.getValue("e-err").status)
        assertFalse(byId.getValue("e-err").safeReason.orEmpty().contains("/home/alice"))
        assertTrue(byId.getValue("e-err").safeReason.orEmpty().contains("<path>"))
    }
}
