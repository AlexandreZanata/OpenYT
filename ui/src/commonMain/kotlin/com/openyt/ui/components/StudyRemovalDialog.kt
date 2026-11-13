package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.StudyExportOptions
import com.openyt.core.application.StudyHostLinkCapability
import com.openyt.core.application.StudyMarkdownExporter
import com.openyt.core.application.TextExportOutcome
import com.openyt.core.application.TextFileExporter
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.StudyRemovalPreview
import com.openyt.ui.i18n.LocalStrings
import kotlinx.coroutines.launch

/**
 * Confirmation preview dialog shown before removing library record(s) that have
 * authored study content. Original media files are always left untouched on disk.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyRemovalDialog(
    previews: List<StudyRemovalPreview>,
    exporter: TextFileExporter?,
    hostLinkCapability: StudyHostLinkCapability,
    language: AppLanguage,
    onConfirmRemoveAndPurge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val studyStrings = strings.study
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val totalNotes = previews.sumOf { it.noteCount }
    val totalBookmarks = previews.sumOf { it.bookmarkCount }
    val totalRanges = previews.sumOf { it.savedRangeCount }
    val anyStudied = previews.any { it.isMarkedStudied }
    val sampleNotes = previews.flatMap { it.notes }.take(3)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                studyStrings.removalDialogTitle,
                modifier = Modifier.testTag("study-removal-title"),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    studyStrings.removalAuthoredCounts(
                        totalNotes,
                        totalBookmarks,
                        totalRanges,
                        anyStudied,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    studyStrings.removalMediaPreservedNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sampleNotes.forEach { note ->
                    val prefix = StudyMarkdownExporter.formatInterval(note.timestampMillis, note.endMillis)
                    Text(
                        "• [$prefix] ${note.body}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                exportStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (exporter?.canExport == true) {
                    TextButton(
                        enabled = !exporting,
                        onClick = {
                            scope.launch {
                                exporting = true
                                try {
                                    val combined = previews.joinToString("\n\n---\n\n") { preview ->
                                        StudyMarkdownExporter.exportRemovalPreview(
                                            preview = preview,
                                            options = StudyExportOptions(
                                                includeLocalLinks = hostLinkCapability.supportsClickableEntryLinks,
                                                language = language,
                                            ),
                                            hostCapability = hostLinkCapability,
                                        )
                                    }
                                    val titleHint = previews.firstOrNull()?.entryTitle ?: "openyt-study"
                                    val filename = StudyMarkdownExporter.suggestedFilename(titleHint)
                                    exportStatus = when (val outcome = exporter.exportMarkdown(filename, combined)) {
                                        is TextExportOutcome.Exported -> studyStrings.exportSuccess(outcome.destinationLabel)
                                        TextExportOutcome.Canceled -> studyStrings.exportCanceled
                                        is TextExportOutcome.Failed -> studyStrings.exportFailed(outcome.message)
                                    }
                                } finally {
                                    exporting = false
                                }
                            }
                        },
                        modifier = Modifier.testTag("study-removal-export"),
                    ) {
                        Text(studyStrings.exportMarkdownAction)
                    }
                }
                TextButton(
                    onClick = onConfirmRemoveAndPurge,
                    modifier = Modifier.testTag("study-removal-confirm"),
                ) {
                    Text(studyStrings.removalConfirmDeleteAllAction)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("study-removal-cancel"),
            ) {
                Text(strings.cancelAction)
            }
        },
    )
}
