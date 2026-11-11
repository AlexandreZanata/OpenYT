package com.openyt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.openyt.core.application.MediaActions
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.DownloadStatus
import com.openyt.ui.components.DownloadProgressBar
import com.openyt.ui.components.ProviderIcon
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.ShareableMedia
import com.openyt.ui.components.MediaThumbnail
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.failureMessage
import com.openyt.ui.util.formatBytes
import com.openyt.ui.util.formatDuration
import com.openyt.ui.util.formatSpeed
import com.openyt.ui.util.statusLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(
    coordinator: DownloadCoordinator,
    mediaActions: MediaActions,
    playbackLauncher: PlaybackLauncher? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val tasks by coordinator.tasks.collectAsState()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        val selectableIds = tasks.filter { it.status.isFinished }.map { it.id }.toSet()
        val activeSelection = selected.intersect(selectableIds)
        val launcher = playbackLauncher?.takeIf { it.canPlay }
        val playableTasks = tasks.filter {
            it.status == DownloadStatus.COMPLETED && !it.filePath.isNullOrBlank()
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                strings.downloadsTitle,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (wide && activeSelection.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            coordinator.dismissAll(activeSelection)
                            selected = emptySet()
                        },
                    ) {
                        Text(strings.dismissSelected)
                    }
                }
                if (tasks.any { it.status == DownloadStatus.INTERRUPTED }) {
                    TextButton(onClick = { coordinator.resumeInterrupted() }) {
                        Text(strings.resumeInterrupted)
                    }
                }
                if (tasks.any { it.status.isFinished }) {
                    TextButton(
                        onClick = {
                            coordinator.clearFinished()
                            selected = emptySet()
                        },
                    ) {
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
                        val playAction: (() -> Unit)? = if (
                            launcher != null &&
                            task.status == DownloadStatus.COMPLETED &&
                            !task.filePath.isNullOrBlank()
                        ) {
                            val index = playableTasks.indexOfFirst { it.id == task.id }
                            if (index >= 0) {
                                {
                                    launcher.play(
                                        items = playableTasks.map { playable ->
                                            PlayableMedia(
                                                path = playable.filePath.orEmpty(),
                                                title = playable.media.title,
                                                kind = playable.request.kind,
                                            )
                                        },
                                        startIndex = index,
                                    )
                                }
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                        DownloadTaskCard(
                            task = task,
                            wide = wide,
                            selectable = wide && task.status.isFinished,
                            selected = task.id in activeSelection,
                            onSelect = { checked ->
                                selected = if (checked) selected + task.id else selected - task.id
                            },
                            onCancel = { coordinator.cancel(task.id) },
                            onPause = { coordinator.pause(task.id) },
                            onResume = { coordinator.resume(task.id) },
                            onRetry = { coordinator.retry(task.id) },
                            onDismiss = { coordinator.dismiss(task.id) },
                            onPriorityChange = { coordinator.setPriority(task.id, it) },
                            onPlay = playAction,
                            mediaActions = mediaActions,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    wide: Boolean,
    selectable: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPriorityChange: (DownloadPriority) -> Unit,
    onPlay: (() -> Unit)?,
    mediaActions: MediaActions,
) {
    val strings = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selectable) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onSelect,
                        modifier = Modifier.semantics {
                            contentDescription = task.media.title
                        },
                    )
                }
                if (!task.media.thumbnailUrl.isNullOrBlank()) {
                    MediaThumbnail(
                        url = task.media.thumbnailUrl,
                        modifier = Modifier
                            .width(if (wide) 240.dp else 96.dp)
                            .aspectRatio(16f / 9f),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProviderIcon(task.media.source, Modifier.size(18.dp))
                        Text(
                            task.media.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        statusLabel(task.status, strings),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (task.status == DownloadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )

                    if (task.status == DownloadStatus.DOWNLOADING) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            DownloadProgressBar(
                                title = task.media.title,
                                percent = task.percent,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${task.percent.toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        val details = listOfNotNull(
                            formatSpeed(task.speedBytesPerSecond),
                            formatDuration(task.etaSeconds)?.let { "${strings.etaLabel} $it" },
                        ).joinToString(" · ")
                        if (details.isNotBlank()) {
                            Text(
                                details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (task.status == DownloadStatus.PROCESSING) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = "${task.media.title}: ${strings.statusProcessing}"
                            },
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
                }
            }

            val completedPath = task.filePath
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onPlay != null) {
                    TextButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = strings.playAction)
                        Text(strings.playAction)
                    }
                }
                when {
                    task.status.isActive -> {
                        PriorityMenu(
                            current = task.priority,
                            onSelect = onPriorityChange,
                        )
                        TextButton(onClick = onPause) { Text(strings.pauseAction) }
                        TextButton(onClick = onCancel) { Text(strings.cancelAction) }
                    }

                    task.status == DownloadStatus.PAUSED -> {
                        TextButton(onClick = onResume) { Text(strings.resumeAction) }
                        TextButton(onClick = onCancel) { Text(strings.cancelAction) }
                    }

                    task.status == DownloadStatus.FAILED ||
                        task.status == DownloadStatus.INTERRUPTED -> {
                        TextButton(onClick = onRetry) { Text(strings.retryAction) }
                        TextButton(onClick = onDismiss) { Text(strings.dismissAction) }
                    }

                    task.status == DownloadStatus.COMPLETED && completedPath != null -> {
                        val shareable = ShareableMedia(
                            path = completedPath,
                            title = task.media.title,
                            kind = task.request.kind,
                        )
                        if (mediaActions.canShare) {
                            TextButton(
                                onClick = {
                                    mediaActions.share(
                                        listOf(shareable),
                                        strings.shareChooserTitle,
                                    )
                                },
                            ) {
                                Text(strings.shareAction)
                            }
                        }
                        if (mediaActions.canOpen) {
                            TextButton(onClick = { mediaActions.open(shareable) }) {
                                Text(strings.openAction)
                            }
                        }
                        if (mediaActions.canReveal) {
                            TextButton(onClick = { mediaActions.reveal(shareable) }) {
                                Text(strings.revealAction)
                            }
                        }
                        TextButton(onClick = onDismiss) { Text(strings.dismissAction) }
                    }

                    else ->
                        TextButton(onClick = onDismiss) { Text(strings.dismissAction) }
                }
            }
        }
    }
}

@Composable
private fun PriorityMenu(
    current: DownloadPriority,
    onSelect: (DownloadPriority) -> Unit,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("${strings.priorityLabel}: ${priorityLabel(current, strings)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DownloadPriority.entries.forEach { priority ->
                DropdownMenuItem(
                    text = { Text(priorityLabel(priority, strings)) },
                    onClick = {
                        expanded = false
                        onSelect(priority)
                    },
                )
            }
        }
    }
}

private fun priorityLabel(
    priority: DownloadPriority,
    strings: com.openyt.ui.i18n.AppStrings,
): String = when (priority) {
    DownloadPriority.HIGH -> strings.priorityHigh
    DownloadPriority.NORMAL -> strings.priorityNormal
    DownloadPriority.LOW -> strings.priorityLow
}
