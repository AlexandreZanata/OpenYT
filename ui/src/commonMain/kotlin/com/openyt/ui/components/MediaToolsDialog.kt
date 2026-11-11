package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.MediaOperation
import com.openyt.core.application.MediaProcessingRequest
import com.openyt.core.application.MediaProcessor
import com.openyt.core.application.parseMediaSegment
import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaSegment
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.failureMessage
import com.openyt.ui.util.fileNameOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaToolsDialog(entry: LibraryEntry, processor: MediaProcessor, coordinator: DownloadCoordinator, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var operation by remember { mutableStateOf(MediaOperation.TRIM) }
    var start by remember { mutableStateOf("0") }
    var end by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(AudioPreset.M4A) }
    var normalize by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val segment = parseMediaSegment(start, end)
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(strings.features[FeatureText.PROCESSING]) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, maxLines = 2)
                Text(strings.features[FeatureText.PRESERVE_ORIGINAL])
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(operation == MediaOperation.TRIM, { operation = MediaOperation.TRIM }, label = { Text(strings.features[FeatureText.TRIM]) })
                    FilterChip(operation == MediaOperation.CONVERT_AUDIO, { operation = MediaOperation.CONVERT_AUDIO }, label = { Text(strings.features[FeatureText.CONVERT]) })
                }
                if (operation == MediaOperation.TRIM) {
                    OutlinedTextField(start, { start = it }, label = { Text(strings.features[FeatureText.START_SECONDS]) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(end, { end = it }, label = { Text(strings.features[FeatureText.END_SECONDS]) }, modifier = Modifier.fillMaxWidth())
                    Text(strings.features[FeatureText.KEYFRAME_HINT], style = MaterialTheme.typography.bodySmall)
                    if (segment == null) Text(strings.features[FeatureText.INVALID_RANGE], color = MaterialTheme.colorScheme.error)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioPreset.entries.forEach { choice ->
                            FilterChip(preset == choice, { preset = choice }, label = { Text(choice.name) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(normalize, { normalize = it })
                        Text(strings.features[FeatureText.NORMALIZE])
                    }
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working && (operation != MediaOperation.TRIM || segment != null), onClick = {
                working = true
                message = strings.features[FeatureText.WORKING]
                scope.launch {
                    try {
                        when (val result = processor.process(MediaProcessingRequest(entry.filePath, entry.title, entry.kind, operation, segment, preset, normalize))) {
                            is EngineResult.Success -> {
                                val output = result.value
                                coordinator.addLibraryEntries(listOf(LibraryEntry(output.id, output.title, output.kind, output.path, output.sizeBytes, source = entry.source)))
                                message = "${strings.features[FeatureText.DONE]} · ${fileNameOf(output.path)}"
                            }
                            is EngineResult.Failure -> message = failureMessage(result.failure, strings)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = strings.features[FeatureText.OPERATION_FAILED]
                    } finally { working = false }
                }
            }) { Text(if (working) strings.features[FeatureText.WORKING] else strings.bulkApply) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text(strings.closeAction) } },
    )
}
