package com.openyt.engine.ytdlp

import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadEvent

/**
 * Maps low-level tool failures to structured, user-presentable failures. Raw tool
 * output is intentionally not carried into the domain.
 */
object YtDlpErrorMapper {
    fun fromToolOutput(output: String): DownloadFailure {
        val text = output.lowercase()
        return when {
            text.contains("unsupported url") -> DownloadFailure.UnsupportedSource
            text.contains("is not a valid url") || text.contains("invalid url") ->
                DownloadFailure.InvalidUrl("rejected by engine")

            text.contains("no space left") || text.contains("disk full") ->
                DownloadFailure.InsufficientSpace

            text.contains("permission denied") -> DownloadFailure.PermissionDenied
            text.contains("ffmpeg") && text.contains("not found") ->
                DownloadFailure.DependencyMissing("ffmpeg")

            text.contains("ffprobe") && text.contains("not found") ->
                DownloadFailure.DependencyMissing("ffprobe")

            text.contains("video unavailable") ||
                text.contains("this video is unavailable") ||
                text.contains("private video") ||
                text.contains("has been removed") ||
                text.contains("not available in your country") ->
                DownloadFailure.Unavailable(null)

            text.contains("http error 403") || text.contains("http error 429") ->
                DownloadFailure.Unavailable("access denied by source")

            text.contains("timed out") ||
                text.contains("connection") ||
                text.contains("network is unreachable") ||
                text.contains("temporary failure in name resolution") ->
                DownloadFailure.Network

            text.contains("requested format is not available") ->
                DownloadFailure.ProcessingFailed("requested format is not available")

            text.isBlank() -> DownloadFailure.Unexpected(null)
            else -> DownloadFailure.ProcessingFailed(null)
        }
    }
}

/**
 * Parses the machine-readable progress template used by both hosts:
 * `OPENYT_PROGRESS:<percent>|<downloaded>|<total>|<speed>|<eta>`
 *
 * Fields may be `NA` when yt-dlp does not know the value.
 */
object YtDlpProgressParser {
    private const val MARKER = "OPENYT_PROGRESS:"

    fun parse(line: String): DownloadEvent.Progress? {
        val payload = line.substringAfter(MARKER, missingDelimiterValue = "").trim()
        if (payload.isEmpty()) return null

        val parts = payload.split('|')
        val percent = parts.getOrNull(0)?.trimEnd('%')?.trim()?.toDoubleOrNull() ?: return null
        return DownloadEvent.Progress(
            percent = percent.coerceIn(0.0, 100.0),
            downloadedBytes = parts.getOrNull(1).toLongOrNullSafe(),
            totalBytes = parts.getOrNull(2).toLongOrNullSafe(),
            speedBytesPerSecond = parts.getOrNull(3).toLongOrNullSafe(),
            etaSeconds = parts.getOrNull(4).toLongOrNullSafe(),
        )
    }

    private fun String?.toLongOrNullSafe(): Long? {
        if (this == null) return null
        val value = trim()
        if (value.isEmpty() || value.equals("NA", ignoreCase = true)) return null
        return value.toDoubleOrNull()?.toLong()
    }
}

object YtDlpOutputParser {
    private const val PROGRESS_MARKER = "OPENYT_PROGRESS:"

    fun extractFilePath(stdout: String): String? =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("[") || it.startsWith(PROGRESS_MARKER) }
            .filterNot { it.startsWith("WARNING") || it.startsWith("ERROR") }
            .lastOrNull { line -> looksLikePath(line) }

    private fun looksLikePath(line: String): Boolean =
        line.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(line)
}
