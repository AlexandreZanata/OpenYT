package com.openyt.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.domain.LibraryEntry
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.formatBytes

@Composable
fun LibraryScreen(
    coordinator: DownloadCoordinator,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val entries by coordinator.library.collectAsState()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        val knownIds = entries.map { it.id }.toSet()
        val activeSelection = selected.intersect(knownIds)

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
            }

            if (entries.isEmpty()) {
                Text(
                    strings.libraryEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        LibraryCard(
                            entry = entry,
                            selectable = wide,
                            selected = entry.id in activeSelection,
                            onSelect = { checked ->
                                selected = if (checked) selected + entry.id else selected - entry.id
                            },
                            onRemove = { coordinator.removeFromLibrary(entry.id) },
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
}

@Composable
private fun LibraryCard(
    entry: LibraryEntry,
    selectable: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val strings = LocalStrings.current
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
            formatBytes(entry.sizeBytes)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRemove) { Text(strings.removeFromLibrary) }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
