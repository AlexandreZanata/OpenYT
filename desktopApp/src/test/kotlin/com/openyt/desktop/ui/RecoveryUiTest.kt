package com.openyt.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.openyt.core.application.CandidateMatchConfidence
import com.openyt.core.application.CandidateScanProgress
import com.openyt.core.application.CandidateScanResult
import com.openyt.core.application.DuplicateMatchKind
import com.openyt.core.application.EnhancedDuplicateGroup
import com.openyt.core.application.LibraryEntryHealth
import com.openyt.core.application.LibraryHealthReport
import com.openyt.core.application.LibraryHealthSummary
import com.openyt.core.application.LibraryRecoveryUiController
import com.openyt.core.application.MediaHealthStatus
import com.openyt.core.application.RankedRecoveryCandidate
import com.openyt.core.application.RelinkContentMode
import com.openyt.core.application.RelinkPlan
import com.openyt.core.application.RelinkReceipt
import com.openyt.core.application.RelinkTransferStrategy
import com.openyt.core.application.RelinkWarningCode
import com.openyt.core.application.ScannedRecoveryCandidate
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.ui.components.RecoveryDialog
import com.openyt.ui.components.RecoveryDialogTab
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.theme.OpenYtTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
@OptIn(ExperimentalTestApi::class)
class RecoveryUiTest(
    private val case: RecoveryCase,
) {
    data class RecoveryCase(
        val id: String,
        val size: Size,
        val fontScale: Float,
        val strings: AppStrings,
        val darkTheme: Boolean,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<RecoveryCase> = listOf(
            RecoveryCase("narrow-ptbr-2x", Size(320f, 700f), 2f, PtBrStrings, darkTheme = false),
            RecoveryCase("desktop-en-dark", Size(960f, 720f), 1f, EnStrings, darkTheme = true),
        )
    }

    @Test
    fun healthOverviewScanAndConfirmedRelinkFlow() = runSkikoComposeUiTest(
        size = case.size,
        density = Density(1f, case.fontScale),
    ) {
        val missingEntry = sampleEntry("e-missing", "Missing Lecture", "/missing/lecture.mp4")
        val permEntry = sampleEntry("e-perm", "Restricted Audio", "content://saf/doc/perm")
        val changedEntry = sampleEntry("e-changed", "Mutated Talk", "/media/mutated.mp4")
        val healthyEntry = sampleEntry("e-ok", "Healthy Clip", "/media/healthy.mp4")

        val fake = FakeRecoveryUiController(
            report = LibraryHealthReport(
                generatedAtEpochMillis = 1_700_000_000_000L,
                entries = listOf(
                    LibraryEntryHealth(missingEntry, MediaHealthStatus.MISSING),
                    LibraryEntryHealth(permEntry, MediaHealthStatus.PERMISSION_LOST),
                    LibraryEntryHealth(changedEntry, MediaHealthStatus.CHANGED_CONTENT),
                    LibraryEntryHealth(healthyEntry, MediaHealthStatus.AVAILABLE),
                ),
                summary = LibraryHealthSummary(
                    totalEntries = 4,
                    availableCount = 1,
                    missingCount = 1,
                    permissionLostCount = 1,
                    changedContentCount = 1,
                    unknownCount = 0,
                    entriesWithStaleAssets = 0,
                ),
            ),
            scanResult = CandidateScanResult(
                exactMatches = listOf(
                    RankedRecoveryCandidate(
                        candidate = ScannedRecoveryCandidate(
                            locator = "/recovered/video-exact.mp4",
                            displayName = "video-exact.mp4",
                            sizeBytes = 2048L,
                            lastModifiedEpochMillis = 1_700_000_000_500L,
                            verifiedSha256 = "abc123",
                        ),
                        confidence = CandidateMatchConfidence.EXACT_CONTENT,
                        verifiedSha256 = "abc123",
                        score = 100,
                    ),
                ),
                heuristicMatches = listOf(
                    RankedRecoveryCandidate(
                        candidate = ScannedRecoveryCandidate(
                            locator = "/recovered/video-alt.mp4",
                            displayName = "video-alt.mp4",
                            sizeBytes = 2100L,
                            lastModifiedEpochMillis = 1_700_000_001_000L,
                            verifiedSha256 = "def999",
                        ),
                        confidence = CandidateMatchConfidence.HEURISTIC_METADATA,
                        verifiedSha256 = "def999",
                        score = 55,
                    ),
                ),
                filesScanned = 12,
            ),
            heuristicPlan = RelinkPlan(
                planId = "plan-1",
                entryId = missingEntry.id,
                entryTitle = missingEntry.title,
                previousPath = missingEntry.filePath,
                previousRevision = missingEntry.mediaRevision,
                targetLocator = "/recovered/video-alt.mp4",
                targetDisplayName = "video-alt.mp4",
                targetRevision = MediaRevision(2100L, 1_700_000_001_000L, "def999"),
                confidence = CandidateMatchConfidence.HEURISTIC_METADATA,
                contentMode = RelinkContentMode.CHANGED_CONTENT_REPLACEMENT,
                transferStrategy = RelinkTransferStrategy.REFERENCE_LOCAL_PATH,
                requiresManualConfirmation = true,
                warnsSubtitleInvalidation = true,
                warnsStaleStudyAnchors = true,
                recommendsBackupBeforeRelink = true,
                warnings = setOf(
                    RelinkWarningCode.REQUIRES_MANUAL_CONFIRMATION,
                    RelinkWarningCode.STUDY_ANCHORS_WILL_BE_MARKED_STALE,
                    RelinkWarningCode.SUBTITLE_INDEX_WILL_BE_INVALIDATED,
                    RelinkWarningCode.RECOMMENDED_PORTABLE_BACKUP_FIRST,
                ),
                confirmationToken = "tok-1",
                createdAtEpochMillis = 1_700_000_002_000L,
            ),
        )

        var backupShortcutClicks = 0
        var recoveredCallbacks = 0

        setContent {
            CompositionLocalProvider(LocalStrings provides case.strings) {
                OpenYtTheme(darkTheme = case.darkTheme) {
                    RecoveryDialog(
                        controller = fake,
                        defaultScanDirectory = "/recovered",
                        onOpenPortableBackup = { backupShortcutClicks++ },
                        onRecovered = { recoveredCallbacks++ },
                        onDismiss = {},
                    )
                }
            }
        }

        val rStrings = case.strings.recovery
        waitForIdle()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.missingStatus))
        onNodeWithText(rStrings.missingStatus).assertIsDisplayed()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.permissionLostStatus))
        onNodeWithText(rStrings.permissionLostStatus).assertIsDisplayed()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.changedContentStatus))
        onNodeWithText(rStrings.changedContentStatus).assertIsDisplayed()

        // Verify "Backup first" shortcut
        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.backupFirstAction))
        onNodeWithText(rStrings.backupFirstAction).performClick()
        assertEquals(1, backupShortcutClicks)

        // Locate missing entry
        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasTestTag("health-locate-e-missing"))
        onNodeWithTag("health-locate-e-missing").performClick()
        waitForIdle()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.scanFoldersAction))
        onNodeWithText(rStrings.scanFoldersAction).performClick()
        waitForIdle()

        // Both exact SHA-256 and heuristic sections are explicitly separated
        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.exactMatchesHeader))
        onNodeWithText(rStrings.exactMatchesHeader).assertIsDisplayed()
        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.heuristicMatchesHeader))
        onNodeWithText(rStrings.heuristicMatchesHeader).assertIsDisplayed()

        // Choose heuristic candidate -> manual confirmation + warnings required
        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasTestTag("candidate-select-video-alt.mp4"))
        onNodeWithTag("candidate-select-video-alt.mp4").performClick()
        waitForIdle()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.warnStaleStudyAnchors))
        onNodeWithText(rStrings.warnStaleStudyAnchors).assertIsDisplayed()
        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.warnSubtitleInvalidated))
        onNodeWithText(rStrings.warnSubtitleInvalidated).assertIsDisplayed()

        // Confirm action disabled until manual confirmation checkbox is checked
        onNodeWithText(rStrings.confirmRelinkAction).assertIsNotEnabled()
        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasTestTag("recovery-manual-confirm-checkbox"))
        onNodeWithTag("recovery-manual-confirm-checkbox").performClick()
        waitForIdle()

        onNodeWithText(rStrings.confirmRelinkAction).assertIsEnabled()
        onNodeWithText(rStrings.confirmRelinkAction).performClick()
        waitForIdle()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.relinkSuccessTitle))
        onNodeWithText(rStrings.relinkSuccessTitle).assertIsDisplayed()
        assertEquals(1, recoveredCallbacks)
        assertTrue(fake.lastConfirmedContentChange == true)
    }

    @Test
    fun duplicatesTabSeparatesExactVsHeuristicAndDeletesRecordsOnly() = runSkikoComposeUiTest(
        size = case.size,
        density = Density(1f, case.fontScale),
    ) {
        val dup1 = sampleEntry("e-dup-1", "Duplicate Alpha", "/lib/alpha-1.mp4")
        val dup2 = sampleEntry("e-dup-2", "Duplicate Alpha Copy", "/lib/alpha-2.mp4")
        val heu1 = sampleEntry("e-heu-1", "Episode 05 (Original)", "/lib/ep5-orig.mp4")
        val heu2 = sampleEntry("e-heu-2", "Episode 05 (Alt)", "/lib/ep5-alt.mp4")

        val fake = FakeRecoveryUiController(
            duplicates = listOf(
                EnhancedDuplicateGroup(
                    kind = DuplicateMatchKind.EXACT_SHA256,
                    sha256 = "samehash123",
                    entries = listOf(dup1, dup2),
                ),
                EnhancedDuplicateGroup(
                    kind = DuplicateMatchKind.HEURISTIC_METADATA,
                    sha256 = null,
                    entries = listOf(heu1, heu2),
                ),
            ),
            studyCounts = mapOf(
                "e-dup-1" to 4,
                "e-dup-2" to 0,
                "e-heu-1" to 2,
                "e-heu-2" to 1,
            ),
        )

        val removedRecordIds = mutableListOf<String>()

        setContent {
            CompositionLocalProvider(LocalStrings provides case.strings) {
                OpenYtTheme(darkTheme = case.darkTheme) {
                    RecoveryDialog(
                        controller = fake,
                        initialTab = RecoveryDialogTab.DUPLICATES,
                        onRemoveDuplicateRecordsOnly = { ids -> removedRecordIds += ids },
                        onDismiss = {},
                    )
                }
            }
        }

        val rStrings = case.strings.recovery
        waitForIdle()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.duplicatesAdvisoryBanner))
        onNodeWithText(rStrings.duplicatesAdvisoryBanner).assertIsDisplayed()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.exactDuplicatesHeader))
        onNodeWithText(rStrings.exactDuplicatesHeader).assertIsDisplayed()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasText(rStrings.heuristicDuplicatesHeader))
        onNodeWithText(rStrings.heuristicDuplicatesHeader).assertIsDisplayed()

        onNodeWithTag("recovery-dialog-scroll").performScrollToNode(hasTestTag("duplicate-checkbox-e-dup-2"))
        onNodeWithTag("duplicate-checkbox-e-dup-2").performClick()
        waitForIdle()

        onNodeWithText(rStrings.removeRecordsOnlyAction(1)).performClick()
        waitForIdle()

        assertEquals(listOf("e-dup-2"), removedRecordIds)
    }

    private fun sampleEntry(id: String, title: String, path: String) = LibraryEntry(
        id = id,
        title = title,
        kind = MediaKind.VIDEO,
        filePath = path,
        sizeBytes = 2048L,
        createdAtEpochMillis = 1_700_000_000_000L,
        mediaRevision = MediaRevision(2048L, 1_700_000_000_000L, "abc123"),
    )

    private class FakeRecoveryUiController(
        private val report: LibraryHealthReport = LibraryHealthReport(
            generatedAtEpochMillis = 0L,
            entries = emptyList(),
            summary = LibraryHealthSummary(0, 0, 0, 0, 0, 0, 0),
        ),
        private val scanResult: CandidateScanResult = CandidateScanResult(emptyList(), emptyList()),
        private val heuristicPlan: RelinkPlan? = null,
        private val duplicates: List<EnhancedDuplicateGroup> = emptyList(),
        private val studyCounts: Map<String, Int> = emptyMap(),
    ) : LibraryRecoveryUiController {
        var lastConfirmedContentChange: Boolean? = null

        override suspend fun analyzeHealth(verifyHashesWhenUncertain: Boolean): LibraryHealthReport = report

        override suspend fun scanCandidates(
            entry: LibraryEntry,
            folderLocators: List<String>,
            explicitFileLocators: List<String>,
            onProgress: suspend (CandidateScanProgress) -> Unit,
        ): CandidateScanResult = scanResult

        override suspend fun buildRelinkPlan(
            entry: LibraryEntry,
            rankedCandidate: RankedRecoveryCandidate,
        ): Result<RelinkPlan> {
            val plan = heuristicPlan ?: return Result.failure(IllegalStateException("Missing fake plan"))
            return Result.success(plan)
        }

        override suspend fun verifyAndBuildManualPlan(
            entry: LibraryEntry,
            manualLocator: String,
        ): Result<RelinkPlan> {
            val plan = heuristicPlan ?: return Result.failure(IllegalStateException("Missing fake plan"))
            return Result.success(plan)
        }

        override suspend fun executeRelink(
            plan: RelinkPlan,
            confirmedContentChange: Boolean,
        ): Result<RelinkReceipt> {
            lastConfirmedContentChange = confirmedContentChange
            return Result.success(
                RelinkReceipt(
                    operationId = "op-relink-1",
                    entryId = plan.entryId,
                    finalPath = plan.targetLocator,
                    contentMode = plan.contentMode,
                    copiedFromSaf = false,
                    staleAssetsFlagged = 0,
                    subtitleIndexesInvalidated = plan.warnsSubtitleInvalidation,
                ),
            )
        }

        override suspend fun detectDuplicates(verifySameSize: Boolean): List<EnhancedDuplicateGroup> = duplicates

        override suspend fun studyItemCountsByEntry(): Map<String, Int> = studyCounts
    }
}
