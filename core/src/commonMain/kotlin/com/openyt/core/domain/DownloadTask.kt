package com.openyt.core.domain

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELED,
    ;

    val isActive: Boolean
        get() = this == QUEUED || this == DOWNLOADING || this == PROCESSING

    val isFinished: Boolean
        get() = !isActive
}

data class DownloadTask(
    val id: String,
    val media: MediaInfo,
    val request: DownloadRequest,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val percent: Double = 0.0,
    val speedBytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val filePath: String? = null,
    val sizeBytes: Long? = null,
    val failure: DownloadFailure? = null,
)
