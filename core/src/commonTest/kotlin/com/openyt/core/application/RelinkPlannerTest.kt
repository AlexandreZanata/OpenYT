package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RelinkPlannerTest {

    @Test
    fun exactShaMatchProducesIdenticalContentPlanWithoutInvalidationWarnings() {
        val origRev = MediaRevision(10_000L, 100L, "sha-exact-123")
        val entry = LibraryEntry("e1", "Lecture 1", MediaKind.VIDEO, "/old/lecture1.mp4", sizeBytes = 10_000L, mediaRevision = origRev)
        val cand = RankedRecoveryCandidate(
            candidate = ScannedRecoveryCandidate("/new/lecture1-renamed.mp4", "lecture1-renamed.mp4", 10_000L, 200L, MediaKind.VIDEO, "sha-exact-123"),
            confidence = CandidateMatchConfidence.EXACT_CONTENT,
            verifiedSha256 = "sha-exact-123",
            score = 1_000,
        )

        val plan = RelinkPlanner.buildPlan(
            entry = entry,
            rankedCandidate = cand,
            targetIsScopedContentUri = false,
            existingStudyItemCount = 4,
            existingIndexedSubtitleCount = 2,
            existingOfflineAssetCount = 1,
            planId = "plan-1",
            nowEpochMillis = 500L,
        ).getOrThrow()

        assertEquals(RelinkContentMode.IDENTICAL_CONTENT, plan.contentMode)
        assertEquals(RelinkTransferStrategy.REFERENCE_LOCAL_PATH, plan.transferStrategy)
        assertFalse(plan.requiresManualConfirmation)
        assertFalse(plan.warnsSubtitleInvalidation)
        assertFalse(plan.warnsStaleStudyAnchors)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun heuristicReplacementRequiresManualConfirmationAndWarnsStudySubtitlesAndBackup() {
        val origRev = MediaRevision(10_000L, 100L, "sha-orig")
        val entry = LibraryEntry("e1", "Lecture 1", MediaKind.VIDEO, "/old/lecture1.mp4", sizeBytes = 10_000L, mediaRevision = origRev)
        val cand = RankedRecoveryCandidate(
            candidate = ScannedRecoveryCandidate("content://saf/doc/77", "lecture1-reencoded.mp4", 9_800L, 300L, MediaKind.VIDEO, "sha-new"),
            confidence = CandidateMatchConfidence.HEURISTIC_METADATA,
            verifiedSha256 = "sha-new",
            score = 520,
        )

        val plan = RelinkPlanner.buildPlan(
            entry = entry,
            rankedCandidate = cand,
            targetIsScopedContentUri = true,
            existingStudyItemCount = 3,
            existingIndexedSubtitleCount = 1,
            existingOfflineAssetCount = 2,
            planId = "plan-2",
            nowEpochMillis = 600L,
        ).getOrThrow()

        assertEquals(RelinkContentMode.CHANGED_CONTENT_REPLACEMENT, plan.contentMode)
        assertEquals(RelinkTransferStrategy.COPY_SAF_TO_OWNED, plan.transferStrategy)
        assertTrue(plan.requiresManualConfirmation)
        assertTrue(plan.warnsSubtitleInvalidation)
        assertTrue(plan.warnsStaleStudyAnchors)
        assertTrue(plan.recommendsBackupBeforeRelink)
        assertTrue(RelinkWarningCode.REQUIRES_MANUAL_CONFIRMATION in plan.warnings)
        assertTrue(RelinkWarningCode.STUDY_ANCHORS_WILL_BE_MARKED_STALE in plan.warnings)
        assertTrue(RelinkWarningCode.SUBTITLE_INDEX_WILL_BE_INVALIDATED in plan.warnings)
        assertTrue(RelinkWarningCode.OFFLINE_ASSETS_WILL_BE_MARKED_STALE in plan.warnings)
        assertTrue(RelinkWarningCode.RECOMMENDED_PORTABLE_BACKUP_FIRST in plan.warnings)

        // Rejects execution without explicit replacement confirmation
        val missingConfirm = RelinkPlanner.validatePlanCurrent(
            plan = plan,
            currentEntry = entry,
            confirmationToken = plan.confirmationToken,
            confirmedContentChange = false,
        ).exceptionOrNull() as RelinkException
        assertEquals("CONFIRMATION_REQUIRED", missingConfirm.failure.userSafeCode)

        // Rejects stale plan if entry path changed elsewhere
        val staleErr = RelinkPlanner.validatePlanCurrent(
            plan = plan,
            currentEntry = entry.copy(filePath = "/other/path.mp4"),
            confirmationToken = plan.confirmationToken,
            confirmedContentChange = true,
        ).exceptionOrNull() as RelinkException
        assertEquals("STALE_PLAN", staleErr.failure.userSafeCode)

        assertNotEquals("", plan.confirmationToken)
    }
}
