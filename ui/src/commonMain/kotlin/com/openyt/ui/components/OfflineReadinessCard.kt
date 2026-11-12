package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.OfflineAssetView
import com.openyt.core.application.OfflineLibraryState
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.ReadinessStatus
import com.openyt.core.domain.RecoveryAction
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.formatBytes

@Composable
fun OfflineReadinessCard(
    state: OfflineLibraryState,
    onTogglePin: (OfflineAssetView, Boolean) -> Unit,
    onCleanup: (Set<String>) -> Unit,
    onAction: (RecoveryAction) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val offline = strings.offline
    var showCleanup by remember { mutableStateOf(false) }
    val removable = state.assets.filter { it.canCleanup }

    Column(
        modifier = modifier.fillMaxWidth().testTag("offline-readiness"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider()
        Text(offline.readinessLabel, style = MaterialTheme.typography.labelLarge)
        Text(
            readinessLabel(state.readiness.status),
            style = MaterialTheme.typography.bodyMedium,
            color = readinessColor(state.readiness.status),
        )

        Text(offline.missingItems, style = MaterialTheme.typography.labelLarge)
        if (state.readiness.missingRequirements.isEmpty()) {
            Text(
                offline.noMissingItems,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.readiness.missingRequirements.forEach { requirement ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        kindLabel(requirement.kind),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onAction(requirement.suggestedAction) }) {
                        Text(actionLabel(requirement.suggestedAction))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                offline.offlineAssets,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(offline.retryReadiness) }
        }
        if (state.assets.isEmpty()) {
            Text(
                offline.cleanupNone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.assets.forEach { asset ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append(kindLabel(asset.kind))
                                asset.language?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                formatBytes(asset.byteCount),
                                if (asset.isPinned) offline.pinned else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onTogglePin(asset, !asset.isPinned) }) {
                        Text(if (asset.isPinned) offline.unpin else offline.pin)
                    }
                }
            }
        }

        TextButton(
            onClick = { showCleanup = true },
            enabled = removable.isNotEmpty(),
            modifier = Modifier.testTag("offline-cleanup"),
        ) {
            Text(offline.cleanup)
        }
    }

    if (showCleanup) {
        AlertDialog(
            onDismissRequest = { showCleanup = false },
            title = { Text(offline.cleanupTitle) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(offline.cleanupHint, style = MaterialTheme.typography.bodySmall)
                    removable.forEach { asset ->
                        Text(
                            buildString {
                                append(kindLabel(asset.kind))
                                asset.language?.let { append(" · $it") }
                                formatBytes(asset.byteCount)?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCleanup(removable.map { it.id }.toSet())
                        showCleanup = false
                    },
                    modifier = Modifier.testTag("offline-cleanup-confirm"),
                ) {
                    Text(offline.cleanupConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanup = false }) {
                    Text(strings.cancelAction)
                }
            },
        )
    }
}

@Composable
private fun readinessLabel(status: ReadinessStatus): String {
    val offline = LocalStrings.current.offline
    return when (status) {
        ReadinessStatus.READY -> offline.ready
        ReadinessStatus.PENDING -> offline.pending
        ReadinessStatus.INCOMPLETE -> offline.incomplete
        ReadinessStatus.STALE -> offline.stale
        ReadinessStatus.UNAVAILABLE -> offline.unavailable
    }
}

@Composable
private fun readinessColor(status: ReadinessStatus) =
    when (status) {
        ReadinessStatus.READY -> MaterialTheme.colorScheme.primary
        ReadinessStatus.PENDING, ReadinessStatus.STALE -> MaterialTheme.colorScheme.tertiary
        ReadinessStatus.INCOMPLETE, ReadinessStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
    }

@Composable
private fun kindLabel(kind: AssetKind): String {
    val offline = LocalStrings.current.offline
    return when (kind) {
        AssetKind.PRIMARY_MEDIA -> offline.primaryMedia
        AssetKind.ARTWORK -> offline.artwork
        AssetKind.SUBTITLE -> offline.subtitle
        AssetKind.METADATA -> offline.metadata
    }
}

@Composable
private fun actionLabel(action: RecoveryAction): String {
    val offline = LocalStrings.current.offline
    return when (action) {
        RecoveryAction.FETCH_ARTWORK -> offline.actionFetchArtwork
        RecoveryAction.IMPORT_SUBTITLE -> offline.actionImportSubtitle
        RecoveryAction.RELINK_MEDIA -> offline.actionRelink
        RecoveryAction.REVERIFY -> offline.actionReverify
        RecoveryAction.RETRY_DOWNLOAD -> offline.actionRetry
        RecoveryAction.CLEANUP_STALE -> offline.actionCleanup
        RecoveryAction.DISMISS -> offline.actionRetry
    }
}
