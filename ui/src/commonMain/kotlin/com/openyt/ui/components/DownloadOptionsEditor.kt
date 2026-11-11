package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.openyt.core.application.parseMediaSegment
import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaSegment
import com.openyt.core.domain.SubtitleSelection
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadOptionsEditor(
    media: MediaInfo,
    kind: MediaKind,
    options: DownloadOptions,
    onChange: (DownloadOptions) -> Unit,
    onValid: (Boolean) -> Unit,
    onPreview: ((MediaSegment) -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val selectedSubtitle = options.subtitle
    val selectedSegment = options.segment
    var expanded by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var liveSeconds by remember { mutableStateOf("60") }
    fun updateRange(newStart: String, newEnd: String) {
        start = newStart
        end = newEnd
        val segment = parseMediaSegment(start, end)
        onChange(options.copy(segment = segment))
        onValid((start.isBlank() && end.isBlank()) || segment != null)
    }
    TextButton(onClick = { expanded = !expanded }) { Text(strings.features[FeatureText.OPTIONS]) }
    if (expanded || kind == MediaKind.SUBTITLE || media.isLive) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kind == MediaKind.AUDIO) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioPreset.entries.forEach { preset ->
                        FilterChip(options.audioPreset == preset, { onChange(options.copy(audioPreset = preset)) }, label = { Text(preset.name) })
                    }
                }
                OptionCheck(strings.features[FeatureText.NORMALIZE], options.normalizeAudio) { onChange(options.copy(normalizeAudio = it)) }
            }
            if (media.subtitles.isNotEmpty()) {
                Text(strings.features[FeatureText.SUBTITLES])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    media.subtitles.forEach { track ->
                        val selection = SubtitleSelection(track.language, track.automatic, selectedSubtitle?.embed ?: false)
                        FilterChip(
                            selectedSubtitle?.language == track.language && selectedSubtitle.automatic == track.automatic,
                            { onChange(options.copy(subtitle = if (selectedSubtitle == selection) null else selection)) },
                            label = { Text(track.language + if (track.automatic) " (auto)" else "") },
                        )
                    }
                }
                if (kind == MediaKind.VIDEO && selectedSubtitle != null) {
                    OptionCheck(strings.features[FeatureText.EMBED_SUBTITLES], selectedSubtitle.embed) {
                        onChange(options.copy(subtitle = selectedSubtitle.copy(embed = it)))
                    }
                }
            } else if (kind == MediaKind.SUBTITLE) Text(strings.features[FeatureText.NO_SUBTITLES])
            if (kind == MediaKind.VIDEO) {
                OptionCheck(strings.features[FeatureText.CHAPTERS], options.preserveChapters) { onChange(options.copy(preserveChapters = it)) }
                OptionCheck(strings.features[FeatureText.SPONSOR_MARK], options.sponsorBlockMarkers) { onChange(options.copy(sponsorBlockMarkers = it)) }
                if (options.sponsorBlockMarkers) Text(strings.features[FeatureText.SPONSOR_HINT], style = MaterialTheme.typography.bodySmall)
            }
            if (media.isLive) {
                OutlinedTextField(
                    liveSeconds,
                    {
                        liveSeconds = it
                        val seconds = it.toIntOrNull()?.takeIf { value -> value in 1..86_400 }
                        onChange(options.copy(liveDurationSeconds = seconds))
                        onValid(seconds != null)
                    },
                    label = { Text(strings.features[FeatureText.CAPTURE_SECONDS]) },
                    isError = liveSeconds.toIntOrNull()?.let { it in 1..86_400 } != true,
                )
            } else if (kind != MediaKind.SUBTITLE) {
                OutlinedTextField(start, { updateRange(it, end) }, label = { Text(strings.features[FeatureText.START_SECONDS]) })
                OutlinedTextField(end, { updateRange(start, it) }, label = { Text(strings.features[FeatureText.END_SECONDS]) })
                if ((start.isNotBlank() || end.isNotBlank()) && selectedSegment == null) {
                    Text(strings.features[FeatureText.INVALID_RANGE], color = MaterialTheme.colorScheme.error)
                }
                if (onPreview != null && selectedSegment != null) {
                    TextButton(onClick = { onPreview(selectedSegment) }) { Text(strings.features[FeatureText.PREVIEW]) }
                }
            }
        }
    }
}

@Composable
private fun OptionCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}
