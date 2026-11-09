package com.openyt.core.domain

enum class MediaKind {
    VIDEO,
    AUDIO,
    SUBTITLE,
}

data class MediaFormat(
    val id: String,
    val kind: MediaKind,
    val container: String,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrateKbps: Double? = null,
    val sizeBytes: Long? = null,
    val note: String? = null,
)

data class MediaInfo(
    val sourceId: String,
    val source: String,
    val title: String,
    val author: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val webpageUrl: String,
    val formats: List<MediaFormat> = emptyList(),
    val isLive: Boolean = false,
)

data class DownloadRequest(
    val url: String,
    val kind: MediaKind = MediaKind.VIDEO,
    val formatId: String? = null,
    val destinationDirectory: String,
    val fileNameTemplate: String = "%(title).150B [%(id)s].%(ext)s",
)

sealed interface DownloadEvent {
    data object Starting : DownloadEvent

    data class Progress(
        val percent: Double,
        val downloadedBytes: Long? = null,
        val totalBytes: Long? = null,
        val speedBytesPerSecond: Long? = null,
        val etaSeconds: Long? = null,
    ) : DownloadEvent

    data object Processing : DownloadEvent

    data class Completed(
        val filePath: String,
        val sizeBytes: Long? = null,
    ) : DownloadEvent

    data class Failed(val failure: DownloadFailure) : DownloadEvent

    data object Canceled : DownloadEvent
}

sealed interface DownloadFailure {
    data class InvalidUrl(val reason: String) : DownloadFailure
    data object UnsupportedSource : DownloadFailure
    data class Unavailable(val reason: String?) : DownloadFailure
    data object Network : DownloadFailure
    data object InsufficientSpace : DownloadFailure
    data object PermissionDenied : DownloadFailure
    data class DependencyMissing(val component: String) : DownloadFailure
    data class ProcessingFailed(val reason: String?) : DownloadFailure
    data class Unexpected(val reason: String?) : DownloadFailure
}

data class EngineCapabilities(
    val engineName: String,
    val engineVersion: String?,
    val supportsPause: Boolean,
    val supportsSubtitles: Boolean,
)

sealed interface EngineResult<out T> {
    data class Success<T>(val value: T) : EngineResult<T>
    data class Failure(val failure: DownloadFailure) : EngineResult<Nothing>
}
