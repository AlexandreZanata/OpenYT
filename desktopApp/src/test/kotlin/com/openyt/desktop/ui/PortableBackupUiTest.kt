package com.openyt.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.openyt.core.application.PortableArchivePickerPort
import com.openyt.core.application.PortableBackupManifest
import com.openyt.core.application.PortableBackupUiController
import com.openyt.core.application.PortableExportOptions
import com.openyt.core.application.PortableExportProgress
import com.openyt.core.application.PortableExportReceipt
import com.openyt.core.application.PortableRemappedImportPlan
import com.openyt.core.application.PortableRestoreConflictPolicy
import com.openyt.core.application.PortableRestoreOptions
import com.openyt.core.application.PortableRestorePlan
import com.openyt.core.application.PortableRestoreProgress
import com.openyt.core.application.PortableRestoreReceipt
import com.openyt.core.domain.Collection
import com.openyt.ui.components.PortableBackupDialog
import com.openyt.ui.components.PortableBackupDialogTab
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class PortableBackupUiTest(private val scenario: Scenario) {

    @Test
    fun exportShowsPlaintextPrivacyWarningAndCancelableProgress() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        val gate = CompletableDeferred<Unit>()
        var exportedLocator: String? = null
        val controller = FakePortableController(
            onExport = { locator, onProgress ->
                exportedLocator = locator
                onProgress(PortableExportProgress(1, 2, 1024L, 4096L, "video.mp4"))
                gate.await()
                PortableExportReceipt(
                    operationId = "op-exp-1",
                    bundleId = "bundle-1",
                    archiveLocator = locator,
                    archiveSha256 = "abc123def456",
                    entryCount = 2,
                    mediaFileCount = 2,
                    offlineAssetCount = 1,
                    skippedItems = emptyList(),
                    totalBytesWritten = 4096L,
                )
            },
        )
        val picker = FakeArchivePicker(exportPath = "/tmp/test-export.openyt-backup.zip")

        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    PortableBackupDialog(
                        controller = controller,
                        picker = picker,
                        initialTab = PortableBackupDialogTab.EXPORT,
                        onDismiss = {},
                    )
                }
            }
        }

        val pStrings = scenario.strings.portable
        onNodeWithText(pStrings.plaintextWarningTitle).assertIsDisplayed()
        onNodeWithText(pStrings.startExportAction).assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText(pStrings.cancelOperationAction).assertIsDisplayed()

        gate.complete(Unit)
        waitUntil(timeoutMillis = 3_000) {
            onAllNodes(hasText(pStrings.exportSuccessTitle)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("portable-backup-scroll").performScrollToNode(hasText(pStrings.exportSuccessTitle))
        onNodeWithText(pStrings.exportSuccessTitle).assertIsDisplayed()
        assertEquals("/tmp/test-export.openyt-backup.zip", exportedLocator)
    }

    @Test
    fun restoreRequiresConfirmationAfterValidationAndNotifiesHostOnSuccess() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        var restoredCallbacks = 0
        var confirmedSha: String? = null
        val controller = FakePortableController(
            onPrepareRestore = { locator, dest, options, onProgress ->
                onProgress(PortableRestoreProgress("Reading", 1, 2, 512L, 1024L))
                delay(10)
                PortableRestorePlan(
                    operationId = "op-rst-1",
                    bundleId = "bundle-1",
                    archiveLocator = locator,
                    archiveSha256 = "deadbeef00112233",
                    stagingDir = "/tmp/staging",
                    destinationRoot = dest,
                    options = options,
                    manifest = PortableBackupManifest(
                        bundleId = "bundle-1",
                        createdAtEpochMillis = 1_700_000_000_000L,
                    ),
                    importPlan = PortableRemappedImportPlan(
                        conflictPolicy = options.conflictPolicy,
                        entryIdMap = mapOf("old-1" to "new-1"),
                        entriesToImport = emptyList(),
                        skippedDuplicateEntryIds = listOf("dup-1"),
                        collections = listOf(Collection("col-1", "Course")),
                        collectionMembership = emptyList(),
                        smartCollections = emptyList(),
                        customProfiles = emptyList(),
                        skippedProfileIds = emptyList(),
                    ),
                    stagedBlobsByPath = emptyMap(),
                    requiredDestinationBytes = 1024L,
                    availableDestinationBytes = 100_000_000L,
                    storageReserveBytes = 50_000L,
                    hasPlaintextSensitiveData = true,
                    createdAtEpochMillis = 1_700_000_000_000L,
                )
            },
            onExecuteRestore = { _, sha ->
                confirmedSha = sha
                PortableRestoreReceipt(
                    operationId = "op-rst-1",
                    bundleId = "bundle-1",
                    archiveSha256 = sha,
                    entriesRestored = 1,
                    entriesSkipped = 1,
                    mediaFilesCopied = 1,
                    offlineAssetsRestored = 1,
                    collectionsRestored = 1,
                    smartCollectionsRestored = 0,
                    customProfilesRestored = 0,
                    notesRestored = 2,
                    bookmarksRestored = 1,
                    savedRangesRestored = 1,
                    subtitlesReindexed = 1,
                )
            },
        )
        val picker = FakeArchivePicker(restorePath = "/tmp/in.openyt-backup.zip")

        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    PortableBackupDialog(
                        controller = controller,
                        picker = picker,
                        initialTab = PortableBackupDialogTab.RESTORE,
                        onRestored = { restoredCallbacks++ },
                        onDismiss = {},
                    )
                }
            }
        }

        val pStrings = scenario.strings.portable
        onNodeWithText(pStrings.inspectArchiveAction).assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 3_000) {
            onAllNodes(hasText(pStrings.confirmRestoreAction)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("portable-backup-scroll").performScrollToNode(hasText(pStrings.previewTitle))
        onNodeWithText(pStrings.previewTitle).assertIsDisplayed()
        onNodeWithTag("portable-backup-scroll").performScrollToNode(hasText(pStrings.previewPlaintextWarning))
        onNodeWithText(pStrings.previewPlaintextWarning).assertIsDisplayed()
        onNodeWithText(pStrings.confirmRestoreAction).performClick()

        waitUntil(timeoutMillis = 3_000) {
            onAllNodes(hasText(pStrings.restoreSuccessTitle)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("deadbeef00112233", confirmedSha)
        assertTrue(restoredCallbacks == 1)
    }

    private class FakeArchivePicker(
        private val exportPath: String = "/tmp/export.openyt-backup.zip",
        private val restorePath: String = "/tmp/restore.openyt-backup.zip",
    ) : PortableArchivePickerPort {
        override val canPick: Boolean = true
        override fun pickExportLocation(suggestedFileName: String, onPicked: (String?) -> Unit) {
            onPicked(exportPath)
        }
        override fun pickRestoreArchive(onPicked: (String?) -> Unit) {
            onPicked(restorePath)
        }
    }

    private class FakePortableController(
        private val onExport: suspend (String, suspend (PortableExportProgress) -> Unit) -> PortableExportReceipt = { locator, _ ->
            PortableExportReceipt("op", "b", locator, "sha", 0, 0, 0, emptyList(), 0L)
        },
        private val onPrepareRestore: suspend (String, String, PortableRestoreOptions, suspend (PortableRestoreProgress) -> Unit) -> PortableRestorePlan = { _, _, _, _ ->
            error("unused")
        },
        private val onExecuteRestore: suspend (PortableRestorePlan, String) -> PortableRestoreReceipt = { _, _ ->
            error("unused")
        },
    ) : PortableBackupUiController {
        override val appVersion: String = "test"
        override val defaultDestinationRoot: () -> String = { "/tmp/downloads" }

        override suspend fun exportBackup(
            archiveLocator: String,
            options: PortableExportOptions,
            onProgress: suspend (PortableExportProgress) -> Unit,
        ): Result<PortableExportReceipt> = runCatching { onExport(archiveLocator, onProgress) }

        override suspend fun prepareRestorePlan(
            archiveLocator: String,
            destinationRoot: String,
            options: PortableRestoreOptions,
            onProgress: suspend (PortableRestoreProgress) -> Unit,
        ): Result<PortableRestorePlan> = runCatching {
            onPrepareRestore(archiveLocator, destinationRoot, options, onProgress)
        }

        override suspend fun discardPlan(plan: PortableRestorePlan) = Unit

        override suspend fun executeRestore(
            plan: PortableRestorePlan,
            confirmedArchiveSha256: String,
            onProgress: suspend (PortableRestoreProgress) -> Unit,
        ): Result<PortableRestoreReceipt> = runCatching {
            onExecuteRestore(plan, confirmedArchiveSha256)
        }
    }

    data class Scenario(
        val name: String,
        val size: Size,
        val fontScale: Float,
        val strings: AppStrings,
        val dark: Boolean,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Scenario> = listOf(
            Scenario("narrow-ptbr-2x", Size(320f, 700f), 2f, PtBrStrings, dark = false),
            Scenario("desktop-en-dark", Size(960f, 720f), 1f, EnStrings, dark = true),
        )
    }
}
