package com.clipbau.ui.util

import com.clipbau.core.domain.DownloadFailure
import com.clipbau.ui.i18n.AppStrings

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
