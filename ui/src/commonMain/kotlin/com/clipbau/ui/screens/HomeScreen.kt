package com.clipbau.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipbau.core.application.DownloadCoordinator
import com.clipbau.core.application.InspectMediaUseCase
import com.clipbau.core.domain.DownloadEngine
import com.clipbau.core.domain.DownloadFailure
import com.clipbau.core.domain.DownloadRequest
import com.clipbau.core.domain.EngineResult
import com.clipbau.core.domain.MediaFormat
import com.clipbau.core.domain.MediaInfo
import com.clipbau.core.domain.MediaKind
import com.clipbau.ui.i18n.LocalStrings
import com.clipbau.ui.util.failureMessage
import com.clipbau.ui.util.formatDuration
import com.clipbau.ui.util.formatLabel
import kotlinx.coroutines.launch

private sealed interface HomeState {
    data object Idle : HomeState
    data object Loading : HomeState
    data class Ready(val media: MediaInfo, val sourceUrl: String) : HomeState
    data class Error(val failure: DownloadFailure) : HomeState
}

@Composable
fun HomeScreen(
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    downloadsDirectory: String,
    onDownloadStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val inspectMedia = remember(engine) { InspectMediaUseCase(engine) }
    var url by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<HomeState>(HomeState.Idle) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(strings.homeTitle, style = MaterialTheme.typography.headlineMedium)
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

            is HomeState.Ready -> MediaSummary(
                media = current.media,
                sourceUrl = current.sourceUrl,
                coordinator = coordinator,
                downloadsDirectory = downloadsDirectory,
                onDownloadStarted = onDownloadStarted,
            )
        }
    }
}

@Composable
private fun MediaSummary(
    media: MediaInfo,
    sourceUrl: String,
    coordinator: DownloadCoordinator,
    downloadsDirectory: String,
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
            Text(
                media.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
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

private fun defaultFormatId(formats: List<MediaFormat>, kind: MediaKind): String? = when (kind) {
    MediaKind.VIDEO -> formats.maxByOrNull { it.height ?: 0 }?.id
    MediaKind.AUDIO -> formats.maxByOrNull { it.bitrateKbps ?: 0.0 }?.id
    MediaKind.SUBTITLE -> null
}
