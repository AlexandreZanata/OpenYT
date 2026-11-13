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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openyt.core.application.CandidateScanProgress
import com.openyt.core.application.CandidateScanResult
import com.openyt.core.application.DuplicateMatchKind
import com.openyt.core.application.EnhancedDuplicateGroup
import com.openyt.core.application.LibraryEntryHealth
import com.openyt.core.application.LibraryHealthReport
import com.openyt.core.application.LibraryRecoveryUiController
import com.openyt.core.application.MediaHealthStatus
import com.openyt.core.application.RankedRecoveryCandidate
import com.openyt.core.application.RecoveryLocationPickerPort
import com.openyt.core.application.RelinkContentMode
import com.openyt.core.application.RelinkException
import com.openyt.core.application.RelinkFailure
import com.openyt.core.application.RelinkPlan
import com.openyt.core.application.RelinkReceipt
import com.openyt.core.application.RelinkTransferStrategy
import com.openyt.core.domain.LibraryEntry
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.RecoveryStrings
import com.openyt.ui.util.fileNameOf
import com.openyt.ui.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class RecoveryDialogTab {
    HEALTH,
    DUPLICATES,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoveryDialog(
    controller: LibraryRecoveryUiController,
    picker: RecoveryLocationPickerPort? = null,
    initialTab: RecoveryDialogTab = RecoveryDialogTab.HEALTH,
    defaultScanDirectory: String = "",
    onOpenPortableBackup: (() -> Unit)? = null,
    onRemoveDuplicateRecordsOnly: (List<String>) -> Unit = {},
    onRecovered: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val rStrings = strings.recovery
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(initialTab) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Health report & filtering
    var healthReport by remember { mutableStateOf<LibraryHealthReport?>(null) }
    var healthLoading by remember { mutableStateOf(false) }
    var onlyUnhealthy by remember { mutableStateOf(false) }
    var refreshCounter by remember { mutableStateOf(0) }

    // Active recovery target + scan + plan state
    var selectedHealthEntry by remember { mutableStateOf<LibraryEntryHealth?>(null) }
    var scanFolderInput by remember { mutableStateOf(defaultScanDirectory) }
    var manualFileInput by remember { mutableStateOf("") }
    var scanProgress by remember { mutableStateOf<CandidateScanProgress?>(null) }
    var scanResult by remember { mutableStateOf<CandidateScanResult?>(null) }
    var pendingPlan by remember { mutableStateOf<RelinkPlan?>(null) }
    var manualConfirmed by remember { mutableStateOf(false) }
    var executingRelink by remember { mutableStateOf(false) }
    var relinkReceipt by remember { mutableStateOf<RelinkReceipt?>(null) }
    var recoveryError by remember { mutableStateOf<String?>(null) }

    // Duplicate review state
    var duplicatesLoading by remember { mutableStateOf(false) }
    var duplicateGroups by remember { mutableStateOf<List<EnhancedDuplicateGroup>>(emptyList()) }
    var studyCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var selectedDuplicateIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    DisposableEffect(Unit) {
        onDispose { activeJob?.cancel() }
    }

    LaunchedEffect(activeTab, refreshCounter) {
        if (activeTab == RecoveryDialogTab.HEALTH) {
            healthLoading = true
            healthReport = controller.analyzeHealth(verifyHashesWhenUncertain = false)
            healthLoading = false
        } else {
            duplicatesLoading = true
            duplicateGroups = controller.detectDuplicates(verifySameSize = true)
            studyCounts = controller.studyItemCountsByEntry()
            selectedDuplicateIds = emptySet()
            duplicatesLoading = false
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

    fun startScan(entry: LibraryEntry, folders: List<String>, explicitFiles: List<String>) {
        recoveryError = null
        relinkReceipt = null
        pendingPlan = null
        scanResult = null
        scanProgress = CandidateScanProgress(0, 0, "")
        launchTracked(onCancel = { scanProgress = null }) {
            try {
                val res = controller.scanCandidates(entry, folders, explicitFiles) { prog ->
                    scanProgress = prog
                }
                scanProgress = null
                scanResult = res
            } catch (err: Throwable) {
                if (err !is CancellationException) {
                    scanProgress = null
                    recoveryError = mapRelinkError(err, rStrings)
                } else {
                    throw err
                }
            }
        }
    }

    fun inspectManualOrSingle(entry: LibraryEntry, locator: String) {
        recoveryError = null
        pendingPlan = null
        manualConfirmed = false
        launchTracked {
            controller.verifyAndBuildManualPlan(entry, locator)
                .onSuccess { plan -> pendingPlan = plan }
                .onFailure { err -> recoveryError = mapRelinkError(err, rStrings) }
        }
    }

    fun selectCandidate(entry: LibraryEntry, ranked: RankedRecoveryCandidate) {
        recoveryError = null
        pendingPlan = null
        manualConfirmed = false
        launchTracked {
            controller.buildRelinkPlan(entry, ranked)
                .onSuccess { plan -> pendingPlan = plan }
                .onFailure { err -> recoveryError = mapRelinkError(err, rStrings) }
        }
    }

    fun executeRelinkPlan(plan: RelinkPlan) {
        recoveryError = null
        executingRelink = true
        launchTracked(onCancel = { executingRelink = false }) {
            try {
                controller.executeRelink(plan, confirmedContentChange = manualConfirmed)
                    .onSuccess { receipt ->
                        relinkReceipt = receipt
                        pendingPlan = null
                        selectedHealthEntry = null
                        scanResult = null
                        refreshCounter++
                        onRecovered()
                    }
                    .onFailure { err ->
                        recoveryError = mapRelinkError(err, rStrings)
                    }
            } finally {
                executingRelink = false
            }
        }
    }

    val isBusy = activeJob != null || executingRelink

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
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
                    rStrings.dialogTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = activeTab == RecoveryDialogTab.HEALTH,
                        enabled = !isBusy,
                        onClick = { activeTab = RecoveryDialogTab.HEALTH },
                        label = { Text(rStrings.tabHealth) },
                        modifier = Modifier.testTag("recovery-tab-health"),
                    )
                    FilterChip(
                        selected = activeTab == RecoveryDialogTab.DUPLICATES,
                        enabled = !isBusy,
                        onClick = { activeTab = RecoveryDialogTab.DUPLICATES },
                        label = { Text(rStrings.tabDuplicates) },
                        modifier = Modifier.testTag("recovery-tab-duplicates"),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .testTag("recovery-dialog-scroll"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (activeTab == RecoveryDialogTab.HEALTH) {
                        val targetEntry = selectedHealthEntry
                        val currentPlan = pendingPlan
                        when {
                            relinkReceipt != null -> {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            rStrings.relinkSuccessTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(rStrings.relinkSuccessSummary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            currentPlan != null -> {
                                RelinkConfirmationPanel(
                                    plan = currentPlan,
                                    rStrings = rStrings,
                                    manualConfirmed = manualConfirmed,
                                    enabled = !isBusy,
                                    onManualConfirmedChange = { manualConfirmed = it },
                                    onOpenPortableBackup = onOpenPortableBackup,
                                )
                            }

                            targetEntry != null -> {
                                Text(
                                    rStrings.relinkTargetTitle(targetEntry.entry.title),
                                    style = MaterialTheme.typography.titleSmall,
                                )

                                OutlinedTextField(
                                    value = scanFolderInput,
                                    onValueChange = { scanFolderInput = it },
                                    enabled = !isBusy,
                                    label = { Text(rStrings.scanPathLabel) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (picker?.canPickFolders == true) {
                                        OutlinedButton(
                                            enabled = !isBusy,
                                            onClick = {
                                                picker.pickScanFolders { folders ->
                                                    if (folders.isNotEmpty()) {
                                                        scanFolderInput = folders.first()
                                                        startScan(targetEntry.entry, folders, emptyList())
                                                    }
                                                }
                                            },
                                        ) { Text(rStrings.pickFolderAction) }
                                    }
                                    if (picker?.canPickFiles == true) {
                                        OutlinedButton(
                                            enabled = !isBusy,
                                            onClick = {
                                                picker.pickReplacementFiles { files ->
                                                    if (files.size == 1) {
                                                        manualFileInput = files.single()
                                                        inspectManualOrSingle(targetEntry.entry, files.single())
                                                    } else if (files.size > 1) {
                                                        startScan(targetEntry.entry, emptyList(), files)
                                                    }
                                                }
                                            },
                                        ) { Text(rStrings.pickFilesAction) }
                                    }
                                    Button(
                                        enabled = !isBusy && scanFolderInput.isNotBlank(),
                                        onClick = {
                                            startScan(targetEntry.entry, listOf(scanFolderInput.trim()), emptyList())
                                        },
                                    ) { Text(rStrings.scanFoldersAction) }
                                }

                                OutlinedTextField(
                                    value = manualFileInput,
                                    onValueChange = { manualFileInput = it },
                                    enabled = !isBusy,
                                    label = { Text(rStrings.manualPathLabel) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedButton(
                                    enabled = !isBusy && manualFileInput.isNotBlank(),
                                    onClick = { inspectManualOrSingle(targetEntry.entry, manualFileInput) },
                                ) { Text(rStrings.verifyManualAction) }

                                scanProgress?.let { prog ->
                                    Text(
                                        rStrings.scanningStatus(prog.filesScanned, prog.hashesComputed),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }

                                scanResult?.let { result ->
                                    if (result.truncatedByLimit) {
                                        Text(
                                            rStrings.truncatedWarning,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                    if (result.exactMatches.isEmpty() && result.heuristicMatches.isEmpty()) {
                                        Text(rStrings.noCandidatesFound, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (result.exactMatches.isNotEmpty()) {
                                        Text(
                                            rStrings.exactMatchesHeader,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        result.exactMatches.forEach { ranked ->
                                            CandidateCard(
                                                ranked = ranked,
                                                selectLabel = rStrings.selectCandidateAction,
                                                onSelect = { selectCandidate(targetEntry.entry, ranked) },
                                            )
                                        }
                                    }
                                    if (result.heuristicMatches.isNotEmpty()) {
                                        Text(
                                            rStrings.heuristicMatchesHeader,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                        Text(rStrings.heuristicAdvisoryNote, style = MaterialTheme.typography.bodySmall)
                                        result.heuristicMatches.forEach { ranked ->
                                            CandidateCard(
                                                ranked = ranked,
                                                selectLabel = rStrings.selectCandidateAction,
                                                onSelect = { selectCandidate(targetEntry.entry, ranked) },
                                            )
                                        }
                                    }
                                }
                            }

                            healthLoading -> {
                                Text(rStrings.checkingHealthStatus, style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }

                            else -> {
                                val report = healthReport
                                if (report != null) {
                                    Text(
                                        rStrings.summaryTotals(
                                            report.summary.availableCount,
                                            report.summary.missingCount,
                                            report.summary.permissionLostCount,
                                            report.summary.changedContentCount,
                                            report.summary.entriesWithStaleAssets,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Checkbox(
                                            checked = onlyUnhealthy,
                                            onCheckedChange = { onlyUnhealthy = it },
                                        )
                                        Text(
                                            rStrings.onlyUnhealthyFilter,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedButton(onClick = { refreshCounter++ }) {
                                            Text(rStrings.recheckHealthAction)
                                        }
                                        if (onOpenPortableBackup != null) {
                                            OutlinedButton(onClick = onOpenPortableBackup) {
                                                Text(rStrings.backupFirstAction)
                                            }
                                        }
                                    }

                                    val visible = report.entries.filter {
                                        !onlyUnhealthy || it.needsRecovery || it.staleAssets.isNotEmpty()
                                    }
                                    if (visible.isEmpty() && report.summary.unhealthyCount == 0) {
                                        Text(
                                            rStrings.allEntriesHealthy,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        visible.forEach { item ->
                                            HealthEntryCard(
                                                item = item,
                                                rStrings = rStrings,
                                                onLocate = {
                                                    relinkReceipt = null
                                                    recoveryError = null
                                                    selectedHealthEntry = item
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (executingRelink) {
                            Text(rStrings.executingRelinkStatus, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        recoveryError?.let { err ->
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        // Duplicates advisory review tab
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Text(
                                rStrings.duplicatesAdvisoryBanner,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }

                        if (duplicatesLoading) {
                            Text(rStrings.detectingDuplicatesStatus, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else if (duplicateGroups.isEmpty()) {
                            Text(rStrings.noDuplicatesFound, style = MaterialTheme.typography.bodySmall)
                        } else {
                            val exactGroups = duplicateGroups.filter { it.kind == DuplicateMatchKind.EXACT_SHA256 }
                            val heuristicGroups = duplicateGroups.filter { it.kind == DuplicateMatchKind.HEURISTIC_METADATA }

                            if (exactGroups.isNotEmpty()) {
                                Text(
                                    rStrings.exactDuplicatesHeader,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                exactGroups.forEach { group ->
                                    DuplicateGroupCard(
                                        group = group,
                                        studyCounts = studyCounts,
                                        selectedIds = selectedDuplicateIds,
                                        studyLabel = rStrings.studyCountLabel,
                                        onToggleId = { id ->
                                            selectedDuplicateIds = if (id in selectedDuplicateIds) {
                                                selectedDuplicateIds - id
                                            } else {
                                                selectedDuplicateIds + id
                                            }
                                        },
                                    )
                                }
                            }

                            if (heuristicGroups.isNotEmpty()) {
                                Text(
                                    rStrings.heuristicDuplicatesHeader,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                                heuristicGroups.forEach { group ->
                                    DuplicateGroupCard(
                                        group = group,
                                        studyCounts = studyCounts,
                                        selectedIds = selectedDuplicateIds,
                                        studyLabel = rStrings.studyCountLabel,
                                        onToggleId = { id ->
                                            selectedDuplicateIds = if (id in selectedDuplicateIds) {
                                                selectedDuplicateIds - id
                                            } else {
                                                selectedDuplicateIds + id
                                            }
                                        },
                                    )
                                }
                            }
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
                        OutlinedButton(onClick = { activeJob?.cancel() }) {
                            Text(strings.portable.cancelOperationAction)
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text(strings.closeAction)
                        }
                    }

                    if (!isBusy && activeTab == RecoveryDialogTab.HEALTH) {
                        val currentPlan = pendingPlan
                        when {
                            currentPlan != null -> {
                                OutlinedButton(onClick = { pendingPlan = null }) {
                                    Text(rStrings.backToReportAction)
                                }
                                Button(
                                    enabled = !currentPlan.requiresManualConfirmation || manualConfirmed,
                                    onClick = { executeRelinkPlan(currentPlan) },
                                ) {
                                    Text(rStrings.confirmRelinkAction)
                                }
                            }

                            selectedHealthEntry != null -> {
                                OutlinedButton(
                                    onClick = {
                                        selectedHealthEntry = null
                                        scanResult = null
                                        recoveryError = null
                                    },
                                ) {
                                    Text(rStrings.backToReportAction)
                                }
                            }
                        }
                    } else if (!isBusy && activeTab == RecoveryDialogTab.DUPLICATES && selectedDuplicateIds.isNotEmpty()) {
                        Button(
                            onClick = {
                                val toRemove = selectedDuplicateIds.toList()
                                onRemoveDuplicateRecordsOnly(toRemove)
                                selectedDuplicateIds = emptySet()
                                refreshCounter++
                                onRecovered()
                            },
                        ) {
                            Text(rStrings.removeRecordsOnlyAction(selectedDuplicateIds.size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthEntryCard(
    item: LibraryEntryHealth,
    rStrings: RecoveryStrings,
    onLocate: () -> Unit,
) {
    val badgeText = when (item.status) {
        MediaHealthStatus.AVAILABLE -> rStrings.healthyStatus
        MediaHealthStatus.MISSING -> rStrings.missingStatus
        MediaHealthStatus.PERMISSION_LOST -> rStrings.permissionLostStatus
        MediaHealthStatus.CHANGED_CONTENT -> rStrings.changedContentStatus
        MediaHealthStatus.UNKNOWN -> rStrings.unknownStatus
    }
    val badgeColor = when (item.status) {
        MediaHealthStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
        MediaHealthStatus.MISSING,
        MediaHealthStatus.PERMISSION_LOST,
        -> MaterialTheme.colorScheme.error
        MediaHealthStatus.CHANGED_CONTENT,
        MediaHealthStatus.UNKNOWN,
        -> MaterialTheme.colorScheme.secondary
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.entry.title, style = MaterialTheme.typography.titleSmall)
            Text(badgeText, style = MaterialTheme.typography.labelMedium, color = badgeColor)
            Text(fileNameOf(item.entry.filePath), style = MaterialTheme.typography.bodySmall)
            if (item.staleAssets.isNotEmpty()) {
                Text(
                    rStrings.staleAssetsBadge(item.staleAssets.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (item.needsRecovery) {
                OutlinedButton(
                    onClick = onLocate,
                    modifier = Modifier.testTag("health-locate-${item.entry.id}"),
                ) {
                    Text(rStrings.locateAction)
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    ranked: RankedRecoveryCandidate,
    selectLabel: String,
    onSelect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(ranked.candidate.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                formatBytes(ranked.candidate.sizeBytes) ?: "${ranked.candidate.sizeBytes} B",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onSelect,
                modifier = Modifier.testTag("candidate-select-${ranked.candidate.displayName}"),
            ) {
                Text(selectLabel)
            }
        }
    }
}

@Composable
private fun RelinkConfirmationPanel(
    plan: RelinkPlan,
    rStrings: RecoveryStrings,
    manualConfirmed: Boolean,
    enabled: Boolean,
    onManualConfirmedChange: (Boolean) -> Unit,
    onOpenPortableBackup: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(rStrings.previewRelinkTitle, style = MaterialTheme.typography.titleSmall)
        Text("${rStrings.previousPathLabel}: ${fileNameOf(plan.previousPath)}", style = MaterialTheme.typography.bodySmall)
        Text("${rStrings.targetPathLabel}: ${plan.targetDisplayName}", style = MaterialTheme.typography.bodySmall)
        Text(
            if (plan.transferStrategy == RelinkTransferStrategy.COPY_SAF_TO_OWNED) {
                rStrings.transferStrategySafCopy
            } else {
                rStrings.transferStrategyLocalRef
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (plan.contentMode == RelinkContentMode.IDENTICAL_CONTENT) {
                rStrings.modeIdenticalContent
            } else {
                rStrings.modeChangedReplacement
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (plan.contentMode == RelinkContentMode.IDENTICAL_CONTENT) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
        if (plan.warnsStaleStudyAnchors) {
            Text(rStrings.warnStaleStudyAnchors, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (plan.warnsSubtitleInvalidation) {
            Text(rStrings.warnSubtitleInvalidated, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (plan.recommendsBackupBeforeRelink && onOpenPortableBackup != null) {
            OutlinedButton(enabled = enabled, onClick = onOpenPortableBackup) {
                Text(rStrings.backupFirstAction)
            }
        }
        if (plan.requiresManualConfirmation) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = manualConfirmed,
                    enabled = enabled,
                    onCheckedChange = onManualConfirmedChange,
                    modifier = Modifier.testTag("recovery-manual-confirm-checkbox"),
                )
                Text(
                    rStrings.manualConfirmCheckbox,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: EnhancedDuplicateGroup,
    studyCounts: Map<String, Int>,
    selectedIds: Set<String>,
    studyLabel: (Int) -> String,
    onToggleId: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            group.entries.forEach { entry ->
                val studyCount = studyCounts[entry.id] ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = entry.id in selectedIds,
                        onCheckedChange = { onToggleId(entry.id) },
                        modifier = Modifier.testTag("duplicate-checkbox-${entry.id}"),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${fileNameOf(entry.filePath)} · ${formatBytes(entry.sizeBytes) ?: "—"} · ${studyLabel(studyCount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun mapRelinkError(error: Throwable, rStrings: RecoveryStrings): String {
    val failure = (error as? RelinkException)?.failure
    return when (failure) {
        is RelinkFailure.SourceMissing -> rStrings.errorSourceMissing
        is RelinkFailure.PermissionDenied -> rStrings.errorPermissionDenied
        is RelinkFailure.StalePlan -> rStrings.errorStalePlan
        is RelinkFailure.ConfirmationRequired -> rStrings.errorConfirmationRequired
        is RelinkFailure.InsufficientSpace -> rStrings.errorInsufficientSpace
        is RelinkFailure.InvalidCandidate,
        is RelinkFailure.Canceled,
        is RelinkFailure.IoError,
        null,
        -> rStrings.errorGeneric
    }
}
