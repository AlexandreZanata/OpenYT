package com.openyt.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.openyt.core.application.LibraryController
import com.openyt.core.application.LibraryFilter
import com.openyt.core.application.LibraryItem
import com.openyt.core.application.LibraryQuery
import com.openyt.core.application.MediaActions
import com.openyt.core.application.MediaImporter
import com.openyt.core.application.MediaProbe
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaTechnicalInfo
import com.openyt.core.domain.ShareableMedia
import com.openyt.ui.components.MediaThumbnail
import com.openyt.ui.components.ProviderIcon
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.formatBytes
import com.openyt.ui.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    coordinator: DownloadCoordinator,
    fileAvailability: FileAvailability,
    libraryController: LibraryController,
    mediaProbe: MediaProbe,
    mediaActions: MediaActions,
    mediaImporter: MediaImporter?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val entries by coordinator.library.collectAsState()
    val collections by libraryController.collections.collectAsState()
    val membership by libraryController.membership.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var kind by remember { mutableStateOf<MediaKind?>(null) }
    var missingOnly by remember { mutableStateOf(false) }
    var duplicatesOnly by remember { mutableStateOf(false) }
    var checkToken by remember { mutableStateOf(0) }
    var missingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detail by remember { mutableStateOf<LibraryItem?>(null) }
    var sortByTitle by remember { mutableStateOf(false) }
    var confirmRemoveDuplicates by remember { mutableStateOf(false) }
    var selectedCollectionId by remember { mutableStateOf<String?>(null) }
    var showCreateCollection by remember { mutableStateOf(false) }
    var newCollectionName by remember { mutableStateOf("") }
    var assigningEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var confirmDeleteCollection by remember { mutableStateOf(false) }

    val activeCollectionId = selectedCollectionId?.takeIf { id -> collections.any { it.id == id } }
    val collectionEntries = activeCollectionId?.let { id ->
        entries.filter { it.id in membership[id].orEmpty() }
    } ?: entries

    LaunchedEffect(entries, checkToken) {
        missingIds = withContext(Dispatchers.Default) {
            entries.filterNot { fileAvailability.exists(it.filePath) }.map { it.id }.toSet()
        }
    }

    val filter = LibraryFilter(query, kind, missingOnly, duplicatesOnly)
    val items = remember(collectionEntries, missingIds, filter) {
        LibraryQuery.apply(collectionEntries, missingIds, filter)
    }
    val sortedItems = remember(items, sortByTitle) {
        if (sortByTitle) items.sortedBy { it.entry.title.lowercase() } else items
    }
    val duplicateRemovalIds = remember(collectionEntries) {
        LibraryQuery.duplicateRemovalIds(collectionEntries)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        val visibleIds = sortedItems.map { it.entry.id }.toSet()
        val activeSelection = selected.intersect(visibleIds)

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                strings.libraryTitle,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (mediaImporter?.canImport == true) {
                    TextButton(
                        onClick = {
                            mediaImporter.pickAndImport { imported ->
                                coordinator.addLibraryEntries(imported)
                            }
                        },
                    ) {
                        Text(strings.importAction)
                    }
                }
                TextButton(onClick = { checkToken++ }) { Text(strings.verifyFiles) }
                if (duplicateRemovalIds.isNotEmpty()) {
                    TextButton(onClick = { confirmRemoveDuplicates = true }) {
                        Text(strings.removeDuplicates)
                    }
                }
                if (wide && activeSelection.isNotEmpty()) {
                    if (mediaActions.canShare) {
                        TextButton(
                            onClick = {
                                mediaActions.share(
                                    entries.filter { it.id in activeSelection }
                                        .map { it.toShareable() },
                                    strings.shareChooserTitle,
                                )
                            },
                        ) {
                            Text(strings.shareAction)
                        }
                    }
                    TextButton(
                        onClick = {
                            coordinator.removeFromLibrary(activeSelection)
                            selected = emptySet()
                        },
                    ) {
                        Text(strings.removeSelected)
                    }
                }
                if (activeCollectionId != null) {
                    TextButton(onClick = { confirmDeleteCollection = true }) {
                        Text(strings.deleteCollection)
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(strings.searchLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                strings.collectionsLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = activeCollectionId == null,
                    onClick = { selectedCollectionId = null },
                    label = { Text(strings.allCollections) },
                )
                collections.forEach { collection ->
                    FilterChip(
                        selected = activeCollectionId == collection.id,
                        onClick = {
                            selectedCollectionId = if (activeCollectionId == collection.id) {
                                null
                            } else {
                                collection.id
                            }
                        },
                        label = { Text(collection.name) },
                    )
                }
                TextButton(onClick = { showCreateCollection = true }) {
                    Text(strings.newCollection)
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                FilterChip(
                    selected = sortByTitle,
                    onClick = { sortByTitle = !sortByTitle },
                    label = { Text(strings.sortTitle) },
                )
            }

            when {
                entries.isEmpty() -> Text(
                    strings.libraryEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                sortedItems.isEmpty() -> Text(
                    strings.noResults,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(sortedItems, key = { it.entry.id }) { item ->
                        LibraryCard(
                            item = item,
                            wide = wide,
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
                            onCollections = { assigningEntry = item.entry },
                            onRemove = { coordinator.removeFromLibrary(item.entry.id) },
                            mediaActions = mediaActions,
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
        LibraryDetailsDialog(item = item, mediaProbe = mediaProbe, onClose = { detail = null })
    }

    if (showCreateCollection) {
        AlertDialog(
            onDismissRequest = {
                showCreateCollection = false
                newCollectionName = ""
            },
            title = { Text(strings.newCollection) },
            text = {
                OutlinedTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    label = { Text(strings.collectionName) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newCollectionName.isNotBlank(),
                    onClick = {
                        val id = libraryController.createCollection(newCollectionName)
                        if (id != null) selectedCollectionId = id
                        newCollectionName = ""
                        showCreateCollection = false
                    },
                ) {
                    Text(strings.createAction)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        newCollectionName = ""
                        showCreateCollection = false
                    },
                ) {
                    Text(strings.cancelAction)
                }
            },
        )
    }

    assigningEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { assigningEntry = null },
            title = { Text(strings.assignCollections) },
            text = {
                if (collections.isEmpty()) {
                    Text(strings.noCollections)
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        collections.forEach { collection ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = entry.id in membership[collection.id].orEmpty(),
                                    onCheckedChange = {
                                        libraryController.toggleEntry(collection.id, entry.id)
                                    },
                                )
                                Text(collection.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { assigningEntry = null }) {
                    Text(strings.closeAction)
                }
            },
            dismissButton = if (collections.isEmpty()) {
                {
                    TextButton(
                        onClick = {
                            assigningEntry = null
                            showCreateCollection = true
                        },
                    ) {
                        Text(strings.newCollection)
                    }
                }
            } else {
                null
            },
        )
    }

    if (confirmDeleteCollection && activeCollectionId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCollection = false },
            title = { Text(strings.deleteCollection) },
            text = { Text(strings.deleteCollectionHint) },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryController.deleteCollection(activeCollectionId)
                        selectedCollectionId = null
                        confirmDeleteCollection = false
                    },
                ) {
                    Text(strings.deleteCollection)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteCollection = false }) {
                    Text(strings.cancelAction)
                }
            },
        )
    }

    if (confirmRemoveDuplicates) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDuplicates = false },
            title = { Text(strings.removeDuplicates) },
            text = { Text(strings.removeDuplicatesHint) },
            confirmButton = {
                TextButton(
                    onClick = {
                        coordinator.removeFromLibrary(duplicateRemovalIds)
                        confirmRemoveDuplicates = false
                    },
                ) {
                    Text(strings.removeDuplicatesConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveDuplicates = false }) {
                    Text(strings.cancelAction)
                }
            },
        )
    }
}

@Composable
private fun LibraryCard(
    item: LibraryItem,
    wide: Boolean,
    selectable: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onCollections: () -> Unit,
    onRemove: () -> Unit,
    mediaActions: MediaActions,
) {
    val strings = LocalStrings.current
    val entry = item.entry

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
                        modifier = Modifier.semantics { contentDescription = entry.title },
                    )
                }
                if (!entry.thumbnailUrl.isNullOrBlank()) {
                    MediaThumbnail(
                        url = entry.thumbnailUrl,
                        modifier = Modifier
                            .width(if (wide) 240.dp else 96.dp)
                            .aspectRatio(16f / 9f),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProviderIcon(entry.source, Modifier.size(18.dp))
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
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
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (mediaActions.canShare) {
                    TextButton(
                        onClick = {
                            mediaActions.share(
                                listOf(entry.toShareable()),
                                strings.shareChooserTitle,
                            )
                        },
                        enabled = !item.missing,
                    ) {
                        Text(strings.shareAction)
                    }
                }
                if (mediaActions.canOpen) {
                    TextButton(
                        onClick = { mediaActions.open(entry.toShareable()) },
                        enabled = !item.missing,
                    ) {
                        Text(strings.openAction)
                    }
                }
                if (mediaActions.canReveal) {
                    TextButton(onClick = { mediaActions.reveal(entry.toShareable()) }) {
                        Text(strings.revealAction)
                    }
                }
                TextButton(onClick = onDetails) { Text(strings.detailsAction) }
                TextButton(onClick = onCollections) { Text(strings.assignCollections) }
                TextButton(onClick = onRemove) { Text(strings.removeFromLibrary) }
            }
        }
    }
}

@Composable
private fun LibraryDetailsDialog(
    item: LibraryItem,
    mediaProbe: MediaProbe,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val entry = item.entry
    var technical by remember(entry.id) { mutableStateOf<MediaTechnicalInfo?>(null) }
    var analyzing by remember(entry.id) { mutableStateOf(!item.missing) }

    LaunchedEffect(entry.id, item.missing) {
        if (item.missing) {
            analyzing = false
            return@LaunchedEffect
        }
        technical = withContext(Dispatchers.Default) { mediaProbe.probe(entry.filePath) }
        analyzing = false
    }

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
                when {
                    analyzing -> Text(
                        strings.analyzing,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    item.missing -> Unit

                    technical == null -> Text(
                        strings.notAvailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        val info = technical!!
                        info.durationMillis?.let {
                            DetailRow(
                                strings.detailsDuration,
                                formatDuration(it / 1000).orEmpty(),
                            )
                        }
                        if (info.width != null && info.height != null) {
                            DetailRow(strings.detailsResolution, "${info.width}x${info.height}")
                        }
                        info.videoCodec?.let { DetailRow(strings.detailsVideoCodec, it) }
                        info.audioCodec?.let { DetailRow(strings.detailsAudioCodec, it) }
                        info.bitrateKbps?.let { DetailRow(strings.detailsBitrate, "$it kbps") }
                        info.container?.let { DetailRow(strings.detailsContainer, it) }
                    }
                }
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

private fun LibraryEntry.toShareable() = ShareableMedia(
    path = filePath,
    title = title,
    kind = kind,
)

private fun kindLabel(kind: MediaKind, strings: AppStrings): String = when (kind) {
    MediaKind.VIDEO -> strings.kindVideo
    MediaKind.AUDIO -> strings.kindAudio
    MediaKind.SUBTITLE -> kind.name
}
