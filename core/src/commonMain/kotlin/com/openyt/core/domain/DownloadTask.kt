package com.openyt.core.domain

enum class DownloadPriority(val weight: Int) {
    LOW(0),
    NORMAL(1),
    HIGH(2),
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PROCESSING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED,
    INTERRUPTED,
    ;

    val isActive: Boolean
        get() = this == QUEUED || this == DOWNLOADING || this == PROCESSING

    val isPaused: Boolean
        get() = this == PAUSED

    val isFinished: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELED || this == INTERRUPTED
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
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val attempt: Int = 0,
)
