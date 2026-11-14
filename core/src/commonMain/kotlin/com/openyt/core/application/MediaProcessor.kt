package com.openyt.core.application

import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaSegment
import com.openyt.core.domain.MediaRevision
import kotlinx.serialization.Serializable

@Serializable
enum class MediaOperation { TRIM, CONVERT_AUDIO, RENDER_CLIP }

@Serializable
enum class ClipFraming { ORIGINAL, FIT_PORTRAIT, CENTER_CROP }

@Serializable
data class ClipCaption(val startMillis: Long, val endMillis: Long, val text: String)

data class MediaProcessingCapabilities(val preciseClips: Boolean = false, val portrait: Boolean = false, val burnCaptions: Boolean = false, val covers: Boolean = false, val durableBatches: Boolean = false, val previews: Boolean = false)

@Serializable
data class MediaProcessingRequest(
    val sourcePath: String,
    val title: String,
    val kind: MediaKind,
    val operation: MediaOperation,
    val segment: MediaSegment? = null,
    val audioPreset: AudioPreset = AudioPreset.M4A,
    val normalize: Boolean = false,
    val framing: ClipFraming = ClipFraming.ORIGINAL,
    val captions: List<ClipCaption> = emptyList(),
    val coverAtMillis: Long? = null,
    val sourceEntryId: String? = null,
    val sourceRevision: MediaRevision? = null,
    val sourceDurationMillis: Long? = null,
    val jobId: String? = null,
    val outputDirectory: String? = null,
)

@Serializable
data class ProcessedMedia(val id: String, val title: String, val path: String, val kind: MediaKind, val sizeBytes: Long,
    val mediaRevision: MediaRevision? = null, val coverPath: String? = null, val artifactRoot: String? = null, val coverSha256: String? = null)

@Serializable
enum class ClipJobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED, INTERRUPTED }

@Serializable
data class ClipJobRecord(val id: String, val request: MediaProcessingRequest, val sourceSha256: String,
    val status: ClipJobStatus, val output: ProcessedMedia? = null)

interface ClipJobStore {
    suspend fun get(id: String): ClipJobRecord?
    suspend fun save(record: ClipJobRecord)
    suspend fun load(entryId: String): List<ClipJobRecord>
    suspend fun interruptRunning()
    /** Hosts atomically publish the derived library entry and the successful receipt. */
    suspend fun complete(record: ClipJobRecord) = save(record)
}

interface MediaProcessor {
    suspend fun process(request: MediaProcessingRequest): EngineResult<ProcessedMedia>
    suspend fun capabilities(): MediaProcessingCapabilities = MediaProcessingCapabilities()
    suspend fun clipJobs(entryId: String): List<ClipJobRecord> = emptyList()
    suspend fun queueClips(requests: List<MediaProcessingRequest>): Boolean = false
    suspend fun cancelPendingClips(entryId: String) = Unit
    suspend fun previewClip(request: MediaProcessingRequest): EngineResult<ProcessedMedia> = EngineResult.Failure(com.openyt.core.domain.DownloadFailure.DependencyMissing("clip preview"))
    suspend fun releasePreview(output: ProcessedMedia) = Unit
    suspend fun clipDestination(): String? = null
}

fun MediaProcessingRequest.validClip(): Boolean {
    val bounds = segment ?: return false
    val duration = sourceDurationMillis ?: return false
    return !sourceEntryId.isNullOrBlank() && kind == MediaKind.VIDEO && bounds.endMillis <= duration &&
        duration > 0 && (coverAtMillis == null || coverAtMillis in bounds.startMillis until bounds.endMillis) &&
        captions.size <= 100_000 && captions.all { it.startMillis >= 0 && it.endMillis > it.startMillis && it.text.length <= 20_000 } &&
        captions.sumOf { it.text.length.toLong() } <= 10 * 1024 * 1024 &&
        (outputDirectory == null || outputDirectory.isNotBlank() && !outputDirectory.startsWith("content://") && '\u0000' !in outputDirectory) &&
        (operation == MediaOperation.RENDER_CLIP || framing == ClipFraming.ORIGINAL && captions.isEmpty()) &&
        (jobId == null || jobId.matches(Regex("[A-Za-z0-9-]{1,80}")))
}

fun parseMediaSegment(startSeconds: String, endSeconds: String): MediaSegment? {
    fun millis(text: String): Long? = text.replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }
        ?.let { (it * 1000).toLong() }
    val start = millis(startSeconds) ?: return null
    val end = millis(endSeconds) ?: return null
    return if (end > start) MediaSegment(start, end) else null
}

/** Source-bitrate estimate only; keyframes/reencoding/cover bytes can differ. */
fun estimateClipBytes(sourceBytes: Long?, durationMillis: Long?, ranges: List<MediaSegment>): Long? {
    if (sourceBytes == null || sourceBytes <= 0 || durationMillis == null || durationMillis <= 0 || ranges.isEmpty()) return null
    if (ranges.any { it.startMillis < 0 || it.endMillis <= it.startMillis || it.endMillis > durationMillis }) return null
    val totalMillis = ranges.sumOf { (it.endMillis - it.startMillis).toDouble() }
    return (sourceBytes.toDouble() * (totalMillis / durationMillis)).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
}
