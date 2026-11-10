package com.openyt.ui.util

import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.MediaFormat
import com.openyt.ui.i18n.AppStrings

fun failureMessage(failure: DownloadFailure, strings: AppStrings): String = when (failure) {
    is DownloadFailure.InvalidUrl -> strings.errorInvalidUrl
    DownloadFailure.UnsupportedSource -> strings.errorUnsupported
    is DownloadFailure.Unavailable -> strings.errorUnavailable
    DownloadFailure.Network -> strings.errorNetwork
    DownloadFailure.InsufficientSpace -> strings.errorSpace
    DownloadFailure.PermissionDenied -> strings.errorPermission
    is DownloadFailure.DependencyMissing -> strings.errorDependency
    is DownloadFailure.ProcessingFailed -> strings.errorProcessing
    is DownloadFailure.Unexpected -> strings.errorUnexpected
}

fun formatDuration(seconds: Long?): String? {
    if (seconds == null || seconds < 0) return null
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    return if (hours > 0) {
        "$hours:${minutes.twoDigits()}:${remaining.twoDigits()}"
    } else {
        "$minutes:${remaining.twoDigits()}"
    }
}

fun formatBytes(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        "$bytes ${units[index]}"
    } else {
        "${(value * 10).toLong() / 10.0} ${units[index]}"
    }
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')

fun formatLabel(format: MediaFormat): String {
    val bitrate = format.bitrateKbps
    val quality = when {
        format.width != null && format.height != null -> "${format.height}p"
        bitrate != null && bitrate > 0 -> "${bitrate.toInt()} kbps"
        else -> null
    }
    return listOfNotNull(
        quality,
        format.container.uppercase(),
        format.sizeBytes?.let(::formatBytes),
    ).joinToString(" · ")
}

fun formatSpeed(bytesPerSecond: Long?): String? {
    if (bytesPerSecond == null || bytesPerSecond <= 0) return null
    return "${formatBytes(bytesPerSecond)}/s"
}

fun statusLabel(status: DownloadStatus, strings: AppStrings): String = when (status) {
    DownloadStatus.QUEUED -> strings.statusQueued
    DownloadStatus.DOWNLOADING -> strings.statusDownloading
    DownloadStatus.PROCESSING -> strings.statusProcessing
    DownloadStatus.COMPLETED -> strings.statusCompleted
    DownloadStatus.FAILED -> strings.statusFailed
    DownloadStatus.CANCELED -> strings.statusCanceled
    DownloadStatus.INTERRUPTED -> strings.statusInterrupted
    DownloadStatus.PAUSED -> strings.statusPaused
}
