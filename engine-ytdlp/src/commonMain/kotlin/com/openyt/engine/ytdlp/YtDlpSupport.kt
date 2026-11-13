package com.openyt.engine.ytdlp

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull

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
        if (!percent.isFinite()) return null
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
        return value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toLong()
    }
}

object YtDlpOutputParser {
    private const val FILE_MARKER = "OPENYT_FILE:"
    private const val SUBTITLE_MARKER = "OPENYT_SUBTITLES:"
    private const val ARTIFACT_MARKER = "OPENYT_ARTIFACT:"
    private const val THUMBNAIL_MARKER = "OPENYT_THUMBNAIL:"

    const val SUBTITLE_PATH_TEMPLATE = "after_video:$SUBTITLE_MARKER%(requested_subtitles)j"
    const val FILE_PATH_TEMPLATE = "after_move:$FILE_MARKER%(filepath)j"
    const val THUMBNAIL_PATH_TEMPLATE = "after_move:$THUMBNAIL_MARKER%(filepath)j"

    fun extractFilePath(stdout: String, allowSubtitlesAsPrimary: Boolean = true): String? {
        val lines = stdout.lineSequence().map { it.trim().removePrefix("\uFEFF") }.toList()
        val fileLine = lines.lastOrNull { it.startsWith(FILE_MARKER) }
        if (fileLine != null) {
            val value = runCatching {
                Json.parseToJsonElement(fileLine.removePrefix(FILE_MARKER))
            }.getOrNull() as? JsonPrimitive
            val path = value?.takeIf { it.isString }?.content?.let(::sanitizePath)?.takeIf(::looksLikePath)
            if (path != null) return path
        }
        if (!allowSubtitlesAsPrimary) return null
        val subLine = lines.lastOrNull { it.startsWith(SUBTITLE_MARKER) }
        if (subLine != null) {
            val subtitles = runCatching {
                Json.parseToJsonElement(subLine.removePrefix(SUBTITLE_MARKER)) as? JsonObject
            }.getOrNull()
            return subtitles?.values?.mapNotNull { track ->
                val raw = ((track as? JsonObject)?.get("filepath") as? JsonPrimitive)?.content
                raw?.let(::sanitizePath)?.takeIf(::looksLikePath)
            }?.lastOrNull()
        }
        return null
    }

    /**
     * Extracts all companion artifact receipts explicitly emitted by the engine.
     * Never infers or attaches neighboring files.
     */
    fun extractArtifactReceipts(stdout: String): List<CompanionArtifactReceipt> =
        stdout.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .flatMap { line ->
                when {
                    line.startsWith(SUBTITLE_MARKER) -> parseSubtitlesMarker(line.removePrefix(SUBTITLE_MARKER))
                    line.startsWith(THUMBNAIL_MARKER) -> parseThumbnailMarker(line.removePrefix(THUMBNAIL_MARKER))
                    line.startsWith(ARTIFACT_MARKER) -> parseArtifactMarker(line.removePrefix(ARTIFACT_MARKER))
                    else -> emptyList()
                }
            }
            .toList()

    private fun parseSubtitlesMarker(jsonText: String): List<CompanionArtifactReceipt> {
        val root = runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull() ?: return emptyList()
        return root.entries.mapNotNull { (langKey, element) ->
            val trackObj = element as? JsonObject ?: return@mapNotNull null
            val rawPath = ((trackObj["filepath"] as? JsonPrimitive)?.content) ?: return@mapNotNull null
            val path = sanitizePath(rawPath).takeIf(::looksLikePath) ?: return@mapNotNull null
            val ext = ((trackObj["ext"] as? JsonPrimitive)?.content)
            CompanionArtifactReceipt(
                kind = AssetKind.SUBTITLE,
                filePath = path,
                language = langKey.ifBlank { null },
                sourceType = "SUBTITLE_${ext?.uppercase() ?: "EXTRACTED"}",
                timelineBasis = "SOURCE",
            )
        }
    }

    private fun parseThumbnailMarker(jsonText: String): List<CompanionArtifactReceipt> {
        val element = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull() ?: return emptyList()
        val rawPath = when (element) {
            is JsonPrimitive -> element.content
            is JsonObject -> ((element["filepath"] as? JsonPrimitive)?.content)
            else -> null
        } ?: return emptyList()
        val path = sanitizePath(rawPath).takeIf(::looksLikePath) ?: return emptyList()
        return listOf(
            CompanionArtifactReceipt(
                kind = AssetKind.ARTWORK,
                filePath = path,
                sourceType = "THUMBNAIL",
            ),
        )
    }

    private fun parseArtifactMarker(jsonText: String): List<CompanionArtifactReceipt> {
        val element = runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull() ?: return emptyList()
        val rawPath = ((element["filepath"] as? JsonPrimitive)?.content) ?: return emptyList()
        val path = sanitizePath(rawPath).takeIf(::looksLikePath) ?: return emptyList()
        val kindStr = (element["kind"] as? JsonPrimitive)?.content?.uppercase() ?: "METADATA"
        val kind = runCatching { AssetKind.valueOf(kindStr) }.getOrDefault(AssetKind.METADATA)
        val language = (element["language"] as? JsonPrimitive)?.content
        val sourceType = (element["sourceType"] as? JsonPrimitive)?.content
        val timelineBasis = (element["timelineBasis"] as? JsonPrimitive)?.content
        val isRequired = (element["isRequired"] as? JsonPrimitive)?.booleanOrNull ?: false
        return listOf(
            CompanionArtifactReceipt(
                kind = kind,
                filePath = path,
                language = language,
                sourceType = sourceType,
                timelineBasis = timelineBasis,
                isRequired = isRequired,
            ),
        )
    }

    fun sanitizePath(raw: String): String =
        raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    private fun looksLikePath(line: String): Boolean {
        val clean = sanitizePath(line)
        return clean.startsWith("/") || clean.startsWith("\\\\") ||
            Regex("^[A-Za-z]:[\\\\/].*").matches(clean)
    }
}
