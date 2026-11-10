package com.openyt.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.EnqueuePlaylistUseCase
import com.openyt.core.application.InspectMediaUseCase
import com.openyt.core.application.OrganizationPlanner
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaFormat
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.OrganizationPreset
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.PlaylistEntry
import com.openyt.core.domain.PlaylistEntrySelection
import com.openyt.core.domain.PlaylistInfo
import com.openyt.ui.components.BrandMark
import com.openyt.ui.components.MediaThumbnail
import com.openyt.ui.components.ProviderIcon
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.failureMessage
import com.openyt.ui.util.formatDuration
import com.openyt.ui.util.formatLabel
import kotlinx.coroutines.launch

private sealed interface HomeState {
    data object Idle : HomeState
    data object Loading : HomeState
    data class Ready(val inspection: Inspection, val sourceUrl: String) : HomeState
    data class Error(val failure: DownloadFailure) : HomeState
}

@Composable
fun HomeScreen(
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    downloadsDirectory: String,
    onDownloadStarted: () -> Unit,
    organizationPreset: OrganizationPreset = OrganizationPreset.PLAYLIST,
    fileNameTemplate: String? = null,
    initialSharedUrl: String? = null,
    onSharedUrlConsumed: () -> Unit = {},
    showBrand: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val inspectMedia = remember(engine) { InspectMediaUseCase(engine) }
    var url by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<HomeState>(HomeState.Idle) }

    LaunchedEffect(initialSharedUrl) {
        val shared = initialSharedUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        url = shared
        state = HomeState.Loading
        try {
            state = when (val result = inspectMedia(shared)) {
                is EngineResult.Success -> HomeState.Ready(result.value, shared)
                is EngineResult.Failure -> HomeState.Error(result.failure)
            }
        } finally {
            onSharedUrlConsumed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showBrand) {
            BrandMark()
        }
        Text(
            strings.homeTitle,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            strings.homeSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(strings.urlLabel) },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                scope.launch {
                    state = HomeState.Loading
                    val source = url.trim()
                    state = when (val result = inspectMedia(source)) {
                        is EngineResult.Success -> HomeState.Ready(result.value, source)
                        is EngineResult.Failure -> HomeState.Error(result.failure)
                    }
                }
            },
            enabled = state != HomeState.Loading,
        ) {
            if (state == HomeState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text(strings.inspecting)
            } else {
                Text(strings.inspect)
            }
        }

        when (val current = state) {
            HomeState.Idle -> Unit
            HomeState.Loading -> Unit
            is HomeState.Error -> Text(
                text = failureMessage(current.failure, strings),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            is HomeState.Ready -> when (val inspection = current.inspection) {
                is Inspection.Single -> MediaSummary(
                    media = inspection.media,
                    sourceUrl = current.sourceUrl,
                    coordinator = coordinator,
                    downloadsDirectory = downloadsDirectory,
                    organizationPreset = organizationPreset,
                    fileNameTemplate = fileNameTemplate,
                    onDownloadStarted = onDownloadStarted,
                )

                is Inspection.Playlist -> PlaylistSummary(
                    playlist = inspection.playlist,
                    engine = engine,
                    coordinator = coordinator,
                    downloadsDirectory = downloadsDirectory,
                    organizationPreset = organizationPreset,
                    fileNameTemplate = fileNameTemplate,
                    onDownloadStarted = onDownloadStarted,
                )
            }
        }
    }
}

@Composable
private fun MediaSummary(
    media: MediaInfo,
    sourceUrl: String,
    coordinator: DownloadCoordinator,
    downloadsDirectory: String,
    organizationPreset: OrganizationPreset,
    fileNameTemplate: String?,
    onDownloadStarted: () -> Unit,
) {
    val strings = LocalStrings.current
    val hasVideo = media.formats.any { it.kind == MediaKind.VIDEO }
    val hasAudio = media.formats.any { it.kind == MediaKind.AUDIO }

    var kind by remember(media) {
        mutableStateOf(if (hasVideo) MediaKind.VIDEO else MediaKind.AUDIO)
    }
    val formats = remember(media, kind) { media.formats.filter { it.kind == kind } }
    var selectedFormatId by remember(media, kind) { mutableStateOf(defaultFormatId(formats, kind)) }
    var menuOpen by remember { mutableStateOf(false) }
    val selected = formats.firstOrNull { it.id == selectedFormatId }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MediaThumbnail(
                url = media.thumbnailUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderIcon(media.source, Modifier.size(24.dp))
                Text(
                    media.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            val meta = listOfNotNull(
                media.author,
                formatDuration(media.durationSeconds) ?: strings.resultDurationUnknown,
                if (media.isLive) strings.resultLive else null,
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${media.formats.size} ${strings.resultFormats}",
                style = MaterialTheme.typography.labelLarge,
            )

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == MediaKind.VIDEO,
                    onClick = { kind = MediaKind.VIDEO },
                    enabled = hasVideo,
                    label = { Text(strings.kindVideo) },
                )
                FilterChip(
                    selected = kind == MediaKind.AUDIO,
                    onClick = { kind = MediaKind.AUDIO },
                    enabled = hasAudio,
                    label = { Text(strings.kindAudio) },
                )
            }

            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    enabled = formats.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        selected?.let(::formatLabel) ?: strings.bestAvailable,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.heightIn(max = 320.dp),
                    scrollState = rememberScrollState(),
                ) {
                    DropdownMenuItem(
                        text = { Text(strings.bestAvailable) },
                        onClick = {
                            selectedFormatId = null
                            menuOpen = false
                        },
                    )
                    formats.forEach { format ->
                        DropdownMenuItem(
                            text = { Text(formatLabel(format)) },
                            onClick = {
                                selectedFormatId = format.id
                                menuOpen = false
                            },
                        )
                    }
                }
            }

            Text(
                strings.downloadHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    coordinator.enqueue(
                        media = media,
                        request = DownloadRequest(
                            url = sourceUrl,
                            kind = kind,
                            formatId = selectedFormatId,
                            destinationDirectory = downloadsDirectory,
                        ),
                    )
                    onDownloadStarted()
                },
                enabled = formats.isNotEmpty(),
            ) {
                Text(strings.downloadButton)
            }
        }
    }
}

@Composable
private fun PlaylistSummary(
    playlist: PlaylistInfo,
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    downloadsDirectory: String,
    organizationPreset: OrganizationPreset,
    fileNameTemplate: String?,
    onDownloadStarted: () -> Unit,
) {
    val strings = LocalStrings.current
    val enqueuePlaylist = remember(coordinator) { EnqueuePlaylistUseCase(coordinator) }
    var kind by remember(playlist) { mutableStateOf(MediaKind.VIDEO) }
    var selected by remember(playlist) {
        mutableStateOf(playlist.entries.map { it.index }.toSet())
    }
    var overrides by remember(playlist) {
        mutableStateOf<Map<Int, PlaylistEntrySelection>>(emptyMap())
    }
    var formatEntry by remember(playlist) { mutableStateOf<PlaylistEntry?>(null) }
    val selectedEntries = playlist.entries.filter { it.index in selected }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MediaThumbnail(
                url = playlist.thumbnailUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProviderIcon(playlist.source, Modifier.size(24.dp))
                Text(
                    playlist.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            val meta = listOfNotNull(
                playlist.author,
                "${playlist.entries.size} ${strings.playlistItems}",
            ).joinToString(" · ")
            Text(
                meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == MediaKind.VIDEO,
                    onClick = { kind = MediaKind.VIDEO },
                    label = { Text(strings.kindVideo) },
                )
                FilterChip(
                    selected = kind == MediaKind.AUDIO,
                    onClick = { kind = MediaKind.AUDIO },
                    label = { Text(strings.kindAudio) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { selected = playlist.entries.map { it.index }.toSet() },
                ) {
                    Text(strings.selectAll)
                }
                TextButton(onClick = { selected = emptySet() }) {
                    Text(strings.clearSelection)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(playlist.entries, key = { it.index }) { entry ->
                    PlaylistEntryRow(
                        entry = entry,
                        checked = entry.index in selected,
                        override = overrides[entry.index],
                        onCheckedChange = { checked ->
                            selected = if (checked) {
                                selected + entry.index
                            } else {
                                selected - entry.index
                            }
                        },
                        onChooseFormat = { formatEntry = entry },
                    )
                }
            }

            Text(
                strings.playlistHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    enqueuePlaylist(
                        playlist = playlist,
                        entries = selectedEntries,
                        kind = kind,
                        downloadsDirectory = downloadsDirectory,
                        overrides = overrides,
                        preset = organizationPreset,
                        customFileNameTemplate = fileNameTemplate,
                    )
                    onDownloadStarted()
                },
                enabled = selectedEntries.isNotEmpty(),
            ) {
                Text("${strings.downloadSelected} (${selectedEntries.size})")
            }
        }
    }

    val pending = formatEntry
    if (pending != null) {
        FormatPickerDialog(
            entry = pending,
            engine = engine,
            defaultKind = kind,
            current = overrides[pending.index],
            onDismiss = { formatEntry = null },
            onClear = {
                overrides = overrides - pending.index
                formatEntry = null
            },
            onSelect = { selection ->
                overrides = overrides + (pending.index to selection)
                formatEntry = null
            },
        )
    }
}

@Composable
private fun PlaylistEntryRow(
    entry: PlaylistEntry,
    checked: Boolean,
    override: PlaylistEntrySelection?,
    onCheckedChange: (Boolean) -> Unit,
    onChooseFormat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                entry.author,
                entry.durationSeconds?.let(::formatDuration),
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChooseFormat) {
                val strings = LocalStrings.current
                val label = override?.let {
                    val kindLabel = if (it.kind == MediaKind.AUDIO) {
                        strings.kindAudio
                    } else {
                        strings.kindVideo
                    }
                    "${strings.entryFormatAction}: $kindLabel"
                } ?: strings.entryFormatAction
                Text(label)
            }
        }
    }
}


private sealed interface FormatDialogState {
    data object Loading : FormatDialogState
    data class Ready(val media: MediaInfo) : FormatDialogState
    data class Error(val failure: DownloadFailure) : FormatDialogState
}

@Composable
fun FormatPickerDialog(
    entry: PlaylistEntry,
    engine: DownloadEngine,
    defaultKind: MediaKind,
    current: PlaylistEntrySelection?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSelect: (PlaylistEntrySelection) -> Unit,
) {
    val strings = LocalStrings.current
    val inspectMedia = remember(engine) { InspectMediaUseCase(engine) }
    var state by remember(entry.url) {
        mutableStateOf<FormatDialogState>(FormatDialogState.Loading)
    }
    var selectedKind by remember(entry.url) {
        mutableStateOf(current?.kind ?: defaultKind)
    }
    var selectedFormatId by remember(entry.url) {
        mutableStateOf(current?.formatId)
    }

    LaunchedEffect(entry.url) {
        state = when (val result = inspectMedia(entry.url)) {
            is EngineResult.Success -> {
                val inspection = result.value
                if (inspection is Inspection.Single) {
                    FormatDialogState.Ready(inspection.media)
                } else {
                    FormatDialogState.Error(
                        DownloadFailure.ProcessingFailed("playlist entry returned a playlist"),
                    )
                }
            }

            is EngineResult.Failure -> FormatDialogState.Error(result.failure)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                entry.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            when (val currentState = state) {
                FormatDialogState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(strings.entryFormatLoading)
                    }
                }

                is FormatDialogState.Error -> {
                    Text(
                        strings.entryFormatError,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is FormatDialogState.Ready -> {
                    val formats = currentState.media.formats.filter { it.kind == selectedKind }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedKind == MediaKind.VIDEO,
                                onClick = {
                                    selectedKind = MediaKind.VIDEO
                                    selectedFormatId = null
                                },
                                label = { Text(strings.kindVideo) },
                            )
                            FilterChip(
                                selected = selectedKind == MediaKind.AUDIO,
                                onClick = {
                                    selectedKind = MediaKind.AUDIO
                                    selectedFormatId = null
                                },
                                label = { Text(strings.kindAudio) },
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        ) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedFormatId = null },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedFormatId == null,
                                        onClick = { selectedFormatId = null },
                                    )
                                    Text(strings.entryFormatBest)
                                }
                            }
                            items(formats, key = { it.id }) { format ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedKind = format.kind
                                            selectedFormatId = format.id
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedFormatId == format.id,
                                        onClick = {
                                            selectedKind = format.kind
                                            selectedFormatId = format.id
                                        },
                                    )
                                    Text(
                                        formatLabel(format),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                        if (formats.isEmpty()) {
                            Text(
                                strings.resultNoFormats,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelect(PlaylistEntrySelection(selectedKind, selectedFormatId))
                },
                enabled = state is FormatDialogState.Ready,
            ) {
                Text(strings.entryFormatApply)
            }
        },
        dismissButton = {
            TextButton(onClick = onClear) {
                Text(strings.entryFormatUseDefault)
            }
        },
    )
}

private fun defaultFormatId(formats: List<MediaFormat>, kind: MediaKind): String? = when (kind) {
    MediaKind.VIDEO -> formats.maxByOrNull { it.height ?: 0 }?.id
    MediaKind.AUDIO -> formats.maxByOrNull { it.bitrateKbps ?: 0.0 }?.id
    MediaKind.SUBTITLE -> null
}
