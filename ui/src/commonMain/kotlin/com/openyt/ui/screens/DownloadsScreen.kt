package com.openyt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.failureMessage
import com.openyt.ui.util.formatBytes
import com.openyt.ui.util.formatDuration
import com.openyt.ui.util.formatSpeed
import com.openyt.ui.util.statusLabel

@Composable
fun DownloadsScreen(
    coordinator: DownloadCoordinator,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val tasks by coordinator.tasks.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.downloadsTitle,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.weight(1f))
            if (tasks.any { it.status.isFinished }) {
                TextButton(onClick = coordinator::clearFinished) {
                    Text(strings.clearFinished)
                }
            }
        }

        if (tasks.isEmpty()) {
            Text(
                strings.downloadsEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onCancel = { coordinator.cancel(task.id) },
                        onRetry = { coordinator.retry(task.id) },
                        onDismiss = { coordinator.dismiss(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                task.media.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLabel(task.status, strings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PROCESSING) {
                LinearProgressIndicator(
                    progress = { (task.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "${task.media.title}: ${task.percent.toInt()}%"
                        },
                )
                val details = buildList {
                    add("${task.percent.toInt()}%")
                    formatSpeed(task.speedBytesPerSecond)?.let(::add)
                    formatDuration(task.etaSeconds)?.let { add("${strings.etaLabel} $it") }
                }
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            task.failure?.let { failure ->
                Text(
                    failureMessage(failure, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (task.status == DownloadStatus.COMPLETED) {
                val name = task.filePath?.substringAfterLast('/')?.substringAfterLast('\\')
                Text(
                    listOfNotNull(name, formatBytes(task.sizeBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    task.status.isActive ->
                        TextButton(onClick = onCancel) { Text(strings.cancelAction) }

                    task.status == DownloadStatus.FAILED ||
                        task.status == DownloadStatus.INTERRUPTED -> {
                        TextButton(onClick = onRetry) { Text(strings.retryAction) }
                        TextButton(onClick = onDismiss) { Text(strings.dismissAction) }
                    }

                    else ->
                        TextButton(onClick = onDismiss) { Text(strings.dismissAction) }
                }
            }
        }
    }
}
