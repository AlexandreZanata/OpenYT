package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.StorageReport
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.fileNameOf
import com.openyt.ui.util.formatBytes

@Composable
fun StorageDialog(
    report: StorageReport?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onImport: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                strings.storagePanel,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("storage-content"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    loading -> item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Text(strings.analyzing)
                        }
                    }

                    failed || report == null -> item {
                        Text(strings.storageFailed, color = MaterialTheme.colorScheme.error)
                    }

                    else -> {
                        item {
                            Text(strings.storageTotals, style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${report.totalEntries} ${strings.entriesCount} · " +
                                    (formatBytes(report.totalBytes) ?: "0 B"),
                            )
                        }
                        item { StorageSection(strings.storageLargest, report.largest.isEmpty()) }
                        items(report.largest) { entry ->
                            StorageFile(entry.title, entry.filePath, entry.sizeBytes)
                        }
                        item { StorageSection(strings.storageDuplicates, report.duplicateGroups.isEmpty()) }
                        items(report.duplicateGroups) { group ->
                            Text(
                                group.entries.joinToString(" · ") { it.title },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        item { StorageSection(strings.storageMissing, report.missing.isEmpty()) }
                        items(report.missing) { entry ->
                            StorageFile(entry.title, entry.filePath, entry.sizeBytes)
                        }
                        item {
                            StorageSection(strings.storageOrphans, report.orphanFiles.isEmpty())
                            if (report.orphanFiles.isNotEmpty()) {
                                Text(strings.storageOrphanHint, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${report.orphanFiles.size} ${strings.filesCount} · " +
                                        (formatBytes(report.orphanFiles.sumOf { it.sizeBytes }) ?: "0 B"),
                                )
                            }
                        }
                        items(report.orphanFiles) { file ->
                            StorageFile(fileNameOf(file.path), file.path, file.sizeBytes)
                        }
                        item {
                            Text(
                                strings.storageReadOnlyHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.closeAction) }
        },
        dismissButton = {
            if (!loading && (failed || report == null)) {
                TextButton(onClick = onRetry) { Text(strings.retryAction) }
            } else if (!loading && !report?.orphanFiles.isNullOrEmpty() && onImport != null) {
                TextButton(onClick = onImport) { Text(strings.watchFolderImport) }
            }
        },
    )
}

@Composable
private fun StorageSection(title: String, empty: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (empty) Text(LocalStrings.current.storageNone)
    }
}

@Composable
private fun StorageFile(title: String, path: String, sizeBytes: Long?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        formatBytes(sizeBytes)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
    }
}
