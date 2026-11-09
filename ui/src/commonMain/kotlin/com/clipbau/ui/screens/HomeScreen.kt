package com.clipbau.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipbau.core.application.InspectMediaUseCase
import com.clipbau.core.domain.DownloadEngine
import com.clipbau.core.domain.DownloadFailure
import com.clipbau.core.domain.EngineResult
import com.clipbau.core.domain.MediaFormat
import com.clipbau.core.domain.MediaInfo
import com.clipbau.ui.i18n.LocalStrings
import com.clipbau.ui.util.failureMessage
import com.clipbau.ui.util.formatBytes
import com.clipbau.ui.util.formatDuration
import kotlinx.coroutines.launch

private sealed interface HomeState {
    data object Idle : HomeState
    data object Loading : HomeState
    data class Ready(val media: MediaInfo) : HomeState
    data class Error(val failure: DownloadFailure) : HomeState
}

@Composable
fun HomeScreen(
    engine: DownloadEngine,
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
                    state = when (val result = inspectMedia(url)) {
                        is EngineResult.Success -> HomeState.Ready(result.value)
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
            is HomeState.Ready -> MediaSummary(current.media)
        }
    }
}

@Composable
private fun MediaSummary(media: MediaInfo) {
    val strings = LocalStrings.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (media.formats.isEmpty()) {
                Text(
                    strings.resultNoFormats,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HorizontalDivider()
                media.formats.take(8).forEach { format -> FormatRow(format) }
            }
        }
    }
}

@Composable
private fun FormatRow(format: MediaFormat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(format.id)
                    append(" · ")
                    append(format.container)
                    if (format.width != null && format.height != null) {
                        append(" · ")
                        append(format.width)
                        append('x')
                        append(format.height)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            format.note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        format.sizeBytes?.let {
            Text(
                formatBytes(it).orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
