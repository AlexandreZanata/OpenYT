package com.openyt.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.LibraryFilter
import com.openyt.core.application.LibraryItem
import com.openyt.core.application.LibraryQuery
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    coordinator: DownloadCoordinator,
    fileAvailability: FileAvailability,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val entries by coordinator.library.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var kind by remember { mutableStateOf<MediaKind?>(null) }
    var missingOnly by remember { mutableStateOf(false) }
    var duplicatesOnly by remember { mutableStateOf(false) }
    var checkToken by remember { mutableStateOf(0) }
    var missingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detail by remember { mutableStateOf<LibraryItem?>(null) }

    LaunchedEffect(entries, checkToken) {
        missingIds = withContext(Dispatchers.Default) {
            entries.filterNot { fileAvailability.exists(it.filePath) }.map { it.id }.toSet()
        }
    }

    val filter = LibraryFilter(query, kind, missingOnly, duplicatesOnly)
    val items = remember(entries, missingIds, filter) {
        LibraryQuery.apply(entries, missingIds, filter)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        val visibleIds = items.map { it.entry.id }.toSet()
        val activeSelection = selected.intersect(visibleIds)

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.libraryTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.weight(1f))
                if (wide && activeSelection.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            coordinator.removeFromLibrary(activeSelection)
                            selected = emptySet()
                        },
                    ) {
                        Text(strings.removeSelected)
                    }
                }
                TextButton(onClick = { checkToken++ }) {
                    Text(strings.verifyFiles)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(strings.searchLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter.isDefault,
                    onClick = {
                        kind = null
                        missingOnly = false
                        duplicatesOnly = false
                    },
                    label = { Text(strings.filterAll) },
                )
                FilterChip(
                    selected = kind == MediaKind.VIDEO,
                    onClick = { kind = if (kind == MediaKind.VIDEO) null else MediaKind.VIDEO },
                    label = { Text(strings.kindVideo) },
                )
                FilterChip(
                    selected = kind == MediaKind.AUDIO,
                    onClick = { kind = if (kind == MediaKind.AUDIO) null else MediaKind.AUDIO },
                    label = { Text(strings.kindAudio) },
                )
                FilterChip(
                    selected = missingOnly,
                    onClick = { missingOnly = !missingOnly },
                    label = { Text(strings.filterMissing) },
                )
                FilterChip(
                    selected = duplicatesOnly,
                    onClick = { duplicatesOnly = !duplicatesOnly },
                    label = { Text(strings.filterDuplicates) },
                )
            }

            when {
                entries.isEmpty() -> Text(
                    strings.libraryEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                items.isEmpty() -> Text(
                    strings.noResults,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.entry.id }) { item ->
                        LibraryCard(
                            item = item,
                            selectable = wide,
                            selected = item.entry.id in activeSelection,
                            onSelect = { checked ->
                                selected = if (checked) {
                                    selected + item.entry.id
                                } else {
                                    selected - item.entry.id
                                }
                            },
                            onDetails = { detail = item },
                            onRemove = { coordinator.removeFromLibrary(item.entry.id) },
                        )
                    }
                    item {
                        Text(
                            strings.libraryFileKeptHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }

    detail?.let { item ->
        LibraryDetailsDialog(item = item, onClose = { detail = null })
    }
}

@Composable
private fun LibraryCard(
    item: LibraryItem,
    selectable: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    val strings = LocalStrings.current
    val entry = item.entry

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectable) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onSelect,
                        modifier = Modifier.semantics { contentDescription = entry.title },
                    )
                }
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val name = entry.filePath.substringAfterLast('/').substringAfterLast('\\')
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(formatBytes(entry.sizeBytes), kindLabel(entry.kind, strings))
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.missing) {
                Text(
                    strings.missingFile,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (item.duplicate) {
                Text(
                    strings.possibleDuplicate,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDetails) { Text(strings.detailsAction) }
                TextButton(onClick = onRemove) { Text(strings.removeFromLibrary) }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LibraryDetailsDialog(item: LibraryItem, onClose: () -> Unit) {
    val strings = LocalStrings.current
    val entry = item.entry
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text(strings.closeAction) }
        },
        title = { Text(entry.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(strings.detailsPath, entry.filePath)
                formatBytes(entry.sizeBytes)?.let { DetailRow(strings.detailsSize, it) }
                DetailRow(strings.detailsKind, kindLabel(entry.kind, strings))
                DetailRow(
                    strings.detailsStatus,
                    when {
                        item.missing -> strings.missingFile
                        item.duplicate -> strings.possibleDuplicate
                        else -> strings.statusAvailable
                    },
                )
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun kindLabel(kind: MediaKind, strings: AppStrings): String = when (kind) {
    MediaKind.VIDEO -> strings.kindVideo
    MediaKind.AUDIO -> strings.kindAudio
    MediaKind.SUBTITLE -> kind.name
}
