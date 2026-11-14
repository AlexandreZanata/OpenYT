package com.openyt.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openyt.core.application.*
import com.openyt.core.domain.*
import com.openyt.ui.i18n.*
import com.openyt.ui.util.failureMessage
import kotlinx.coroutines.*
import kotlin.random.Random

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchClipsDialog(entry: LibraryEntry, processor: MediaProcessor, coordinator: DownloadCoordinator,
    mediaProbe: MediaProbe?, subtitles: SubtitleIndexRepository?, assets: OfflineAssetStore?,
    playback: PlaybackLauncher?, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var capabilities by remember { mutableStateOf(MediaProcessingCapabilities()) }
    var duration by remember { mutableStateOf<Long?>(null) }
    var destination by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableStateOf("0") }
    var end by remember { mutableStateOf("") }
    var precise by remember { mutableStateOf(false) }
    var framing by remember { mutableStateOf(ClipFraming.ORIGINAL) }
    var burn by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf(false) }
    var coverTime by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<SubtitleTrackIndexRecord>>(emptyList()) }
    var trackId by remember { mutableStateOf<String?>(null) }
    val ranges = remember { mutableStateListOf<MediaProcessingRequest>() }
    var history by remember { mutableStateOf<List<ClipJobRecord>>(emptyList()) }
    val failures = remember { mutableStateMapOf<String, String>() }
    var activeJobId by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var working by remember { mutableStateOf(false) }
    var canceling by remember { mutableStateOf(false) }
    val busy = working || canceling
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ProcessedMedia?>(null) }

    suspend fun releasePreview() {
        val old = preview ?: return
        if (playback?.sessionControl?.loadedMedia?.path == old.path) playback.sessionControl?.stop()
        processor.releasePreview(old)
        preview = null
    }
    LaunchedEffect(entry.id, processor) {
        try {
            capabilities = processor.capabilities()
            destination = processor.clipDestination()
            duration = mediaProbe?.probe(entry.filePath)?.durationMillis
            val revision = entry.mediaRevision
            tracks = subtitles?.loadTrackIndexesForEntry(entry.id)?.filter {
                it.isReady && revision != null && it.mediaRevisionToken == revision.revisionToken
            }.orEmpty()
            trackId = tracks.firstOrNull()?.trackId
            history = processor.clipJobs(entry.id)
            ranges.addAll(history.filter { it.status != ClipJobStatus.SUCCEEDED }.map { it.request })
        } catch (e: CancellationException) { throw e } catch (_: Exception) { message = strings.features[FeatureText.OPERATION_FAILED] }
    }
    DisposableEffect(processor) {
        onDispose {
            val old = preview
            if (old != null) CoroutineScope(Dispatchers.Default).launch {
                if (playback?.sessionControl?.loadedMedia?.path == old.path) playback.sessionControl?.stop()
                processor.releasePreview(old)
            }
        }
    }
    suspend fun draft(): MediaProcessingRequest? {
        val segment = parseMediaSegment(start, end) ?: return null
        val known = duration ?: return null
        val captions = if (precise && burn) trackId?.let { subtitles?.loadActiveCues(it) }?.map { ClipCaption(it.startMs, it.endMs, it.plainText) }.orEmpty() else emptyList()
        if (burn && captions.isEmpty()) return null
        val coverMillis = if (!cover) null else if (coverTime.isBlank()) segment.startMillis + (segment.endMillis - segment.startMillis) / 2
            else coverTime.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }?.times(1000)?.toLong() ?: return null
        return MediaProcessingRequest(entry.filePath, entry.title, entry.kind,
            if (precise) MediaOperation.RENDER_CLIP else MediaOperation.TRIM, segment,
            framing = if (precise) framing else ClipFraming.ORIGINAL, captions = captions,
            coverAtMillis = coverMillis, sourceEntryId = entry.id, sourceRevision = entry.mediaRevision,
            sourceDurationMillis = known, outputDirectory = destination, jobId = "clip-${Random.nextLong().toULong().toString(16)}${Random.nextLong().toULong().toString(16)}").takeIf { it.validClip() }
    }
    fun begin(block: suspend () -> Unit) {
        if (working || canceling) return
        working = true
        operation = scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = strings.features[FeatureText.OPERATION_FAILED] }
            finally { activeJobId = null; working = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.features[ClipText.TITLE]) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title)
                Text(strings.features[FeatureText.PRESERVE_ORIGINAL])
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                destination?.let { Text("${strings.features[ClipText.DESTINATION]}: $it") }
                val estimate = estimateClipBytes(entry.sizeBytes, duration, ranges.filter { request ->
                    history.none { it.id == request.jobId && it.status == ClipJobStatus.SUCCEEDED }
                }.mapNotNull { it.segment })
                if (estimate != null) {
                    Text("${strings.features[ClipText.ESTIMATE]}: ${com.openyt.ui.util.formatBytes(estimate)}")
                    Text(strings.features[ClipText.ESTIMATE_HINT])
                } else Text(strings.features[ClipText.ESTIMATE_UNKNOWN])
                if (duration == null) Text(strings.features[ClipText.NO_DURATION])
                if (!capabilities.preciseClips || !capabilities.burnCaptions) Text(strings.features[ClipText.UNSUPPORTED])
                OutlinedTextField(start, { start = it }, label = { Text(strings.features[FeatureText.START_SECONDS]) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(end, { end = it }, label = { Text(strings.features[FeatureText.END_SECONDS]) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!precise, { precise = false; burn = false; framing = ClipFraming.ORIGINAL }, label = { Text(strings.features[ClipText.COPY]) }, enabled = !busy)
                    FilterChip(precise, { precise = true }, label = { Text(strings.features[ClipText.PRECISE]) }, enabled = !busy && capabilities.preciseClips)
                }
                if (!precise) Text(strings.features[FeatureText.KEYFRAME_HINT])
                if (precise) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(ClipFraming.ORIGINAL to ClipText.ORIGINAL, ClipFraming.FIT_PORTRAIT to ClipText.FIT, ClipFraming.CENTER_CROP to ClipText.CROP).forEach { (choice, label) ->
                            FilterChip(framing == choice, { framing = choice }, label = { Text(strings.features[label]) }, enabled = !busy && (choice == ClipFraming.ORIGINAL || capabilities.portrait))
                        }
                    }
                    if (framing == ClipFraming.CENTER_CROP) Text(strings.features[ClipText.CROP_HINT])
                    FilterChip(burn, { burn = !burn }, label = { Text(strings.features[ClipText.CAPTIONS]) }, enabled = !busy && capabilities.burnCaptions && tracks.isNotEmpty())
                    if (tracks.isEmpty()) Text(strings.features[ClipText.NO_CAPTIONS])
                    if (burn) FlowRow { tracks.forEach { track -> FilterChip(trackId == track.trackId, { trackId = track.trackId }, label = { Text(track.language.orEmpty()) }, enabled = !busy) } }
                }
                FilterChip(cover, { cover = !cover }, label = { Text(strings.features[ClipText.COVER]) }, enabled = !busy && capabilities.covers)
                if (cover) OutlinedTextField(coverTime, { coverTime = it }, label = { Text(strings.features[ClipText.COVER_TIME]) }, enabled = !busy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = !busy && duration != null && capabilities.durableBatches && ranges.size < 100, onClick = {
                        begin { val request = draft(); if (request == null) message = strings.features[FeatureText.INVALID_RANGE] else ranges += request }
                    }) { Text(strings.features[ClipText.ADD]) }
                    TextButton(enabled = !busy && duration != null && capabilities.previews && playback?.sessionControl != null, onClick = {
                        begin {
                            val request = draft()
                            if (request == null) message = strings.features[FeatureText.INVALID_RANGE]
                            else {
                                releasePreview()
                                when (val result = processor.previewClip(request)) {
                                    is EngineResult.Success -> { preview = result.value; playback?.sessionControl?.openAt(listOf(PlayableMedia(result.value.path, strings.features[FeatureText.PREVIEW], MediaKind.VIDEO)), 0, 0) }
                                    is EngineResult.Failure -> message = failureMessage(result.failure, strings)
                                }
                            }
                        }
                    }) { Text(strings.features[ClipText.PREVIEW]) }
                }
                if (playback?.sessionControl == null || !capabilities.previews) Text(strings.features[ClipText.PREVIEW_UNAVAILABLE])
                ranges.toList().forEachIndexed { index, request ->
                    val saved = history.firstOrNull { it.id == request.jobId }
                    val status = when (saved?.status) {
                        ClipJobStatus.SUCCEEDED -> ClipText.SUCCESS
                        ClipJobStatus.FAILED -> ClipText.FAILED
                        ClipJobStatus.INTERRUPTED -> ClipText.INTERRUPTED
                        ClipJobStatus.CANCELED -> ClipText.CANCELED
                        else -> ClipText.PENDING
                    }
                    val statusText = if (request.jobId == activeJobId) strings.features[FeatureText.WORKING] else strings.features[status]
                    Text("${index + 1}. ${request.segment?.startMillis?.div(1000.0)}–${request.segment?.endMillis?.div(1000.0)} s · $statusText")
                    failures[request.jobId.orEmpty()]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    FlowRow {
                        TextButton(enabled = !busy, onClick = { ranges.remove(request) }) { Text(strings.features[ClipText.REMOVE]) }
                        if (index > 0) TextButton(enabled = !busy, onClick = { ranges.removeAt(index); ranges.add(index - 1, request) }) { Text("↑ ${index + 1}") }
                    }
                }
                message?.let { Text(it) }
                if (busy) TextButton(enabled = !canceling, onClick = {
                    val previous = operation
                    canceling = true
                    scope.launch {
                        try { previous?.cancelAndJoin(); processor.cancelPendingClips(entry.id); history = processor.clipJobs(entry.id); message = strings.features[ClipText.CANCELED] }
                        finally { canceling = false }
                    }
                }) { Text(strings.features[ClipText.CANCEL]) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && ranges.isNotEmpty(), onClick = {
                begin {
                    releasePreview()
                    val selected = ranges.toList()
                    if (!processor.queueClips(selected)) { message = strings.features[FeatureText.OPERATION_FAILED]; return@begin }
                    for (request in selected) {
                        if (history.any { it.id == request.jobId && it.status == ClipJobStatus.SUCCEEDED }) continue
                        activeJobId = request.jobId
                        failures.remove(request.jobId.orEmpty())
                        when (val result = processor.process(request)) {
                            is EngineResult.Failure -> failures[request.jobId.orEmpty()] = failureMessage(result.failure, strings)
                            is EngineResult.Success -> coordinator.reloadLibrary()
                        }
                        history = processor.clipJobs(entry.id)
                    }
                    message = "${history.count { it.status == ClipJobStatus.SUCCEEDED }} / ${selected.size} · ${strings.features[ClipText.SUCCESS]}"
                }
            }) { Text(strings.features[ClipText.EXPORT]) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings.closeAction) } },
    )
}
