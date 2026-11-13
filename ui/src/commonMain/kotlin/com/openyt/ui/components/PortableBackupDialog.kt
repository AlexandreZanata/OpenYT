package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openyt.core.application.PortableArchivePickerPort
import com.openyt.core.application.PortableBackupException
import com.openyt.core.application.PortableBackupFailure
import com.openyt.core.application.PortableBackupUiController
import com.openyt.core.application.PortableExportOptions
import com.openyt.core.application.PortableExportProgress
import com.openyt.core.application.PortableExportReceipt
import com.openyt.core.application.PortableRestoreConflictPolicy
import com.openyt.core.application.PortableRestoreOptions
import com.openyt.core.application.PortableRestorePlan
import com.openyt.core.application.PortableRestoreProgress
import com.openyt.core.application.PortableRestoreReceipt
import com.openyt.core.application.defaultPortableBackupFileName
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PortableStrings
import com.openyt.ui.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class PortableBackupDialogTab {
    EXPORT,
    RESTORE,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortableBackupDialog(
    controller: PortableBackupUiController,
    picker: PortableArchivePickerPort? = null,
    initialTab: PortableBackupDialogTab = PortableBackupDialogTab.EXPORT,
    nowEpochMillis: () -> Long = { 0L },
    onRestored: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val pStrings = strings.portable
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(initialTab) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Export state
    var includeMediaFiles by remember { mutableStateOf(true) }
    var includeOfflineAssets by remember { mutableStateOf(true) }
    var allowPartialMissing by remember { mutableStateOf(false) }
    var exportLocator by remember { mutableStateOf("") }
    var exportProgress by remember { mutableStateOf<PortableExportProgress?>(null) }
    var exportReceipt by remember { mutableStateOf<PortableExportReceipt?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    // Restore state
    var restoreLocator by remember { mutableStateOf("") }
    var destinationRoot by remember { mutableStateOf(controller.defaultDestinationRoot()) }
    var conflictPolicy by remember { mutableStateOf(PortableRestoreConflictPolicy.IMPORT_AS_NEW) }
    var restoreProgress by remember { mutableStateOf<PortableRestoreProgress?>(null) }
    var pendingPlan by remember { mutableStateOf<PortableRestorePlan?>(null) }
    var restoreReceipt by remember { mutableStateOf<PortableRestoreReceipt?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            activeJob?.cancel()
            val planToDiscard = pendingPlan
            if (planToDiscard != null) {
                scope.launch { controller.discardPlan(planToDiscard) }
            }
        }
    }

    fun launchTracked(
        onCancel: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        activeJob?.cancel()
        lateinit var launched: Job
        launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancel: CancellationException) {
                onCancel()
                throw cancel
            } finally {
                if (activeJob === launched) {
                    activeJob = null
                }
            }
        }
        activeJob = launched
        launched.start()
    }

    fun startExport(targetLocator: String) {
        if (targetLocator.isBlank()) return
        exportError = null
        exportReceipt = null
        exportProgress = PortableExportProgress(entriesDone = 0, entriesTotal = 0, bytesProcessed = 0L, totalBytes = 0L)
        launchTracked(
            onCancel = {
                exportProgress = null
                exportError = pStrings.errorCanceled
            },
        ) {
            val result = controller.exportBackup(
                archiveLocator = targetLocator.trim(),
                options = PortableExportOptions(
                    includeMediaFiles = includeMediaFiles,
                    includeOfflineAssets = includeOfflineAssets,
                    allowPartialMissing = allowPartialMissing,
                ),
            ) { prog ->
                exportProgress = prog
            }
            exportProgress = null
            result
                .onSuccess { receipt -> exportReceipt = receipt }
                .onFailure { err -> exportError = mapPortableFailureMessage(err, pStrings) }
        }
    }

    fun startInspection(archivePath: String) {
        val trimmed = archivePath.trim()
        if (trimmed.isBlank()) return
        val dest = destinationRoot.trim().ifBlank { controller.defaultDestinationRoot() }
        restoreError = null
        restoreReceipt = null
        pendingPlan?.let { prev -> scope.launch { controller.discardPlan(prev) } }
        pendingPlan = null
        restoreProgress = PortableRestoreProgress(
            phase = pStrings.inspectingStatus,
            itemsDone = 0,
            itemsTotal = 0,
            bytesProcessed = 0L,
            totalBytes = 0L,
        )
        launchTracked(
            onCancel = {
                restoreProgress = null
                restoreError = pStrings.errorCanceled
            },
        ) {
            val result = controller.prepareRestorePlan(
                archiveLocator = trimmed,
                destinationRoot = dest,
                options = PortableRestoreOptions(conflictPolicy = conflictPolicy),
            ) { prog ->
                restoreProgress = prog
            }
            restoreProgress = null
            result
                .onSuccess { plan -> pendingPlan = plan }
                .onFailure { err -> restoreError = mapPortableFailureMessage(err, pStrings) }
        }
    }

    fun startRestoreExecution(plan: PortableRestorePlan) {
        restoreError = null
        restoreProgress = PortableRestoreProgress(
            phase = pStrings.restoringStatus,
            itemsDone = 0,
            itemsTotal = plan.importPlan.entriesToImport.size,
            bytesProcessed = 0L,
            totalBytes = plan.requiredDestinationBytes,
        )
        launchTracked(
            onCancel = {
                restoreProgress = null
                restoreError = pStrings.errorCanceled
            },
        ) {
            val result = controller.executeRestore(
                plan = plan,
                confirmedArchiveSha256 = plan.archiveSha256,
            ) { prog ->
                restoreProgress = prog
            }
            restoreProgress = null
            result
                .onSuccess { receipt ->
                    pendingPlan = null
                    restoreReceipt = receipt
                    onRestored()
                }
                .onFailure { err -> restoreError = mapPortableFailureMessage(err, pStrings) }
        }
    }

    val isBusy = activeJob != null

    Dialog(
        onDismissRequest = {
            if (!isBusy) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(min = 280.dp, max = 560.dp)
                .heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    pStrings.dialogTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = activeTab == PortableBackupDialogTab.EXPORT,
                        enabled = !isBusy,
                        onClick = { activeTab = PortableBackupDialogTab.EXPORT },
                        label = { Text(pStrings.tabExport) },
                    )
                    FilterChip(
                        selected = activeTab == PortableBackupDialogTab.RESTORE,
                        enabled = !isBusy,
                        onClick = { activeTab = PortableBackupDialogTab.RESTORE },
                        label = { Text(pStrings.tabRestore) },
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .testTag("portable-backup-scroll"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (activeTab == PortableBackupDialogTab.EXPORT) {
                        PlaintextNoticeBanner(
                            title = pStrings.plaintextWarningTitle,
                            body = pStrings.plaintextWarningBody,
                        )

                        CheckboxRow(
                            checked = includeMediaFiles,
                            enabled = !isBusy,
                            label = pStrings.includeMediaLabel,
                            onCheckedChange = { includeMediaFiles = it },
                        )
                        CheckboxRow(
                            checked = includeOfflineAssets,
                            enabled = !isBusy,
                            label = pStrings.includeAssetsLabel,
                            onCheckedChange = { includeOfflineAssets = it },
                        )
                        CheckboxRow(
                            checked = allowPartialMissing,
                            enabled = !isBusy,
                            label = pStrings.allowPartialLabel,
                            onCheckedChange = { allowPartialMissing = it },
                        )

                        OutlinedTextField(
                            value = exportLocator,
                            onValueChange = { exportLocator = it },
                            enabled = !isBusy,
                            label = { Text(pStrings.exportLocatorLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (picker?.canPick == true) {
                            OutlinedButton(
                                enabled = !isBusy,
                                onClick = {
                                    val suggested = defaultPortableBackupFileName(nowEpochMillis())
                                    picker.pickExportLocation(suggested) { picked ->
                                        if (!picked.isNullOrBlank()) {
                                            exportLocator = picked
                                        }
                                    }
                                },
                            ) {
                                Text(pStrings.chooseFileAction)
                            }
                        }

                        exportProgress?.let { prog ->
                            Text(
                                buildProgressText(
                                    label = pStrings.exportingStatus,
                                    done = prog.entriesDone,
                                    total = prog.entriesTotal,
                                    bytes = prog.bytesProcessed,
                                    totalBytes = prog.totalBytes,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val fraction = if (prog.totalBytes > 0L) {
                                (prog.bytesProcessed.toFloat() / prog.totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                null
                            }
                            if (fraction != null) {
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        exportError?.let { err ->
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        exportReceipt?.let { rec ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        pStrings.exportSuccessTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        pStrings.exportBundleSummary(
                                            rec.entryCount,
                                            rec.mediaFileCount,
                                            rec.offlineAssetCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (rec.skippedItems.isNotEmpty()) {
                                        Text(
                                            pStrings.exportSkippedWarning(rec.skippedItems.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                    Text(
                                        "${pStrings.exportShaLabel}: ${rec.archiveSha256}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    } else {
                        val currentPlan = pendingPlan
                        val currentReceipt = restoreReceipt
                        when {
                            currentReceipt != null -> {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            pStrings.restoreSuccessTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            pStrings.restoreReceiptSummary(
                                                currentReceipt.entriesRestored,
                                                currentReceipt.entriesSkipped,
                                                currentReceipt.mediaFilesCopied,
                                                currentReceipt.subtitlesReindexed,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }

                            currentPlan != null -> {
                                val planStats = remember(currentPlan) {
                                    val notesCount = currentPlan.importPlan.entriesToImport.sumOf {
                                        it.notes.size + it.bookmarks.size + it.savedRanges.size
                                    }
                                    val assetsCount = currentPlan.importPlan.entriesToImport.sumOf { it.assets.size }
                                    notesCount to assetsCount
                                }
                                if (currentPlan.hasPlaintextSensitiveData) {
                                    PlaintextNoticeBanner(
                                        title = pStrings.plaintextWarningTitle,
                                        body = pStrings.previewPlaintextWarning,
                                    )
                                }
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            pStrings.previewTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            pStrings.previewEntriesCount(
                                                currentPlan.importPlan.entriesToImport.size,
                                                currentPlan.importPlan.skippedDuplicateEntryIds.size,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            pStrings.previewDetailsCounts(
                                                currentPlan.importPlan.collections.size,
                                                currentPlan.importPlan.smartCollections.size,
                                                currentPlan.importPlan.customProfiles.size,
                                                planStats.first,
                                                planStats.second,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            pStrings.previewRequiredSpace(
                                                formatBytes(currentPlan.requiredDestinationBytes) ?: "${currentPlan.requiredDestinationBytes} B",
                                                formatBytes(currentPlan.availableDestinationBytes) ?: "${currentPlan.availableDestinationBytes} B",
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            "${pStrings.destinationFolderLabel}: ${currentPlan.destinationRoot}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            "${pStrings.previewDigestLabel}: ${currentPlan.archiveSha256}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }

                            else -> {
                                Text(pStrings.conflictPolicyLabel, style = MaterialTheme.typography.labelMedium)
                                RadioRow(
                                    selected = conflictPolicy == PortableRestoreConflictPolicy.IMPORT_AS_NEW,
                                    enabled = !isBusy,
                                    label = pStrings.conflictImportAsNew,
                                    onClick = { conflictPolicy = PortableRestoreConflictPolicy.IMPORT_AS_NEW },
                                )
                                RadioRow(
                                    selected = conflictPolicy == PortableRestoreConflictPolicy.SKIP_IF_PRESENT,
                                    enabled = !isBusy,
                                    label = pStrings.conflictSkipExisting,
                                    onClick = { conflictPolicy = PortableRestoreConflictPolicy.SKIP_IF_PRESENT },
                                )

                                OutlinedTextField(
                                    value = destinationRoot,
                                    onValueChange = { destinationRoot = it },
                                    enabled = !isBusy,
                                    label = { Text(pStrings.destinationFolderLabel) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = restoreLocator,
                                    onValueChange = { restoreLocator = it },
                                    enabled = !isBusy,
                                    label = { Text(pStrings.restoreLocatorLabel) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                if (picker?.canPick == true) {
                                    OutlinedButton(
                                        enabled = !isBusy,
                                        onClick = {
                                            picker.pickRestoreArchive { picked ->
                                                if (!picked.isNullOrBlank()) {
                                                    restoreLocator = picked
                                                }
                                            }
                                        },
                                    ) {
                                        Text(pStrings.chooseFileAction)
                                    }
                                }
                            }
                        }

                        restoreProgress?.let { prog ->
                            Text(
                                buildProgressText(
                                    label = prog.phase.ifBlank { pStrings.restoringStatus },
                                    done = prog.itemsDone,
                                    total = prog.itemsTotal,
                                    bytes = prog.bytesProcessed,
                                    totalBytes = prog.totalBytes,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val fraction = if (prog.totalBytes > 0L) {
                                (prog.bytesProcessed.toFloat() / prog.totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                null
                            }
                            if (fraction != null) {
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        restoreError?.let { err ->
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                HorizontalDivider()

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isBusy) {
                        OutlinedButton(
                            onClick = { activeJob?.cancel() },
                        ) {
                            Text(pStrings.cancelOperationAction)
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text(strings.closeAction)
                        }
                    }

                    if (!isBusy && activeTab == PortableBackupDialogTab.EXPORT) {
                        Button(
                            onClick = {
                                if (exportLocator.isNotBlank()) {
                                    startExport(exportLocator)
                                } else if (picker?.canPick == true) {
                                    val suggested = defaultPortableBackupFileName(nowEpochMillis())
                                    picker.pickExportLocation(suggested) { picked ->
                                        if (!picked.isNullOrBlank()) {
                                            exportLocator = picked
                                            startExport(picked)
                                        }
                                    }
                                }
                            },
                            enabled = exportLocator.isNotBlank() || picker?.canPick == true,
                        ) {
                            Text(pStrings.startExportAction)
                        }
                    } else if (!isBusy && activeTab == PortableBackupDialogTab.RESTORE) {
                        val currentPlan = pendingPlan
                        val currentReceipt = restoreReceipt
                        when {
                            currentReceipt != null -> {
                                OutlinedButton(
                                    onClick = {
                                        restoreReceipt = null
                                        restoreError = null
                                    },
                                ) {
                                    Text(pStrings.newOperationAction)
                                }
                            }

                            currentPlan != null -> {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            controller.discardPlan(currentPlan)
                                            pendingPlan = null
                                        }
                                    },
                                ) {
                                    Text(pStrings.discardPlanAction)
                                }
                                Button(
                                    onClick = { startRestoreExecution(currentPlan) },
                                ) {
                                    Text(pStrings.confirmRestoreAction)
                                }
                            }

                            else -> {
                                Button(
                                    onClick = {
                                        if (restoreLocator.isNotBlank()) {
                                            startInspection(restoreLocator)
                                        } else if (picker?.canPick == true) {
                                            picker.pickRestoreArchive { picked ->
                                                if (!picked.isNullOrBlank()) {
                                                    restoreLocator = picked
                                                    startInspection(picked)
                                                }
                                            }
                                        }
                                    },
                                    enabled = restoreLocator.isNotBlank() || picker?.canPick == true,
                                ) {
                                    Text(pStrings.inspectArchiveAction)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaintextNoticeBanner(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RadioRow(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

private fun buildProgressText(
    label: String,
    done: Int,
    total: Int,
    bytes: Long,
    totalBytes: Long,
): String {
    val parts = mutableListOf(label)
    if (total > 0) parts += "$done/$total"
    if (totalBytes > 0L) {
        val processedText = formatBytes(bytes) ?: "$bytes B"
        val totalText = formatBytes(totalBytes) ?: "$totalBytes B"
        parts += "$processedText / $totalText"
    }
    return parts.joinToString(" · ")
}

private fun mapPortableFailureMessage(error: Throwable, pStrings: PortableStrings): String {
    val failure = (error as? PortableBackupException)?.failure
    return when (failure) {
        is PortableBackupFailure.InvalidManifest -> pStrings.errorInvalidManifest
        is PortableBackupFailure.UnsupportedVersion -> pStrings.errorUnsupportedVersion
        is PortableBackupFailure.SecurityViolation -> pStrings.errorSecurityViolation
        is PortableBackupFailure.ChecksumMismatch -> pStrings.errorChecksumMismatch
        is PortableBackupFailure.SizeLimitExceeded -> pStrings.errorSizeLimit
        is PortableBackupFailure.InsufficientSpace -> pStrings.errorInsufficientSpace
        is PortableBackupFailure.SourceMissing -> pStrings.errorSourceMissing
        is PortableBackupFailure.SourceChanged -> pStrings.errorSourceChanged
        is PortableBackupFailure.PermissionDenied -> pStrings.errorPermissionDenied
        is PortableBackupFailure.Canceled -> pStrings.errorCanceled
        is PortableBackupFailure.PlanMismatch,
        is PortableBackupFailure.IoError,
        null,
        -> pStrings.errorGeneric
    }
}
