package com.clipbau.engine.ytdlp

import com.clipbau.core.domain.DownloadFailure
import com.clipbau.core.domain.EngineResult
import com.clipbau.core.domain.MediaFormat
import com.clipbau.core.domain.MediaInfo
import com.clipbau.core.domain.MediaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Converts the JSON emitted by `yt-dlp --dump-single-json` into the domain model.
 *
 * Unknown fields are ignored. Missing optional fields degrade to null rather than
 * failing the inspection, but a document without a usable title/id is rejected.
 */
object YtDlpJsonParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseInspection(raw: String): EngineResult<MediaInfo> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return EngineResult.Failure(DownloadFailure.ProcessingFailed("unreadable metadata"))
        val obj = root as? JsonObject
            ?: return EngineResult.Failure(DownloadFailure.ProcessingFailed("unexpected metadata shape"))

        if (obj.string("_type") == "playlist") {
            return EngineResult.Failure(DownloadFailure.UnsupportedSource)
        }

        val id = obj.string("id")
        val title = obj.string("title") ?: obj.string("fulltitle")
        if (id.isNullOrBlank() || title.isNullOrBlank()) {
            return EngineResult.Failure(DownloadFailure.ProcessingFailed("incomplete metadata"))
        }

        val webpageUrl = obj.string("webpage_url") ?: obj.string("original_url").orEmpty()
        val formats = (obj["formats"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { format(it) }
            .orEmpty()
            .distinctBy { it.id }

        return EngineResult.Success(
            MediaInfo(
                sourceId = id,
                source = (obj.string("extractor") ?: obj.string("extractor_key") ?: "unknown").lowercase(),
                title = title,
                author = obj.string("uploader") ?: obj.string("channel") ?: obj.string("creator"),
                durationSeconds = obj.long("duration"),
                thumbnailUrl = obj.string("thumbnail"),
                webpageUrl = webpageUrl,
                formats = formats,
                isLive = obj.boolean("is_live") == true,
            ),
        )
    }

    private fun format(obj: JsonObject): MediaFormat? {
        val formatId = obj.string("format_id") ?: return null
        val container = obj.string("ext") ?: "unknown"
        val videoCodec = obj.string("vcodec")
        val audioCodec = obj.string("acodec")
        val hasVideo = !videoCodec.isNullOrBlank() && videoCodec != "none"
        val hasAudio = !audioCodec.isNullOrBlank() && audioCodec != "none"

        val kind = when {
            hasVideo -> MediaKind.VIDEO
            hasAudio -> MediaKind.AUDIO
            else -> return null
        }

        return MediaFormat(
            id = formatId,
            kind = kind,
            container = container,
            videoCodec = videoCodec.takeUnless { it == "none" },
            audioCodec = audioCodec.takeUnless { it == "none" },
            width = obj.int("width"),
            height = obj.int("height"),
            bitrateKbps = obj.double("tbr"),
            sizeBytes = obj.long("filesize") ?: obj.long("filesize_approx"),
            note = obj.string("format_note"),
        )
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        this[key] as? JsonPrimitive

    private fun JsonObject.string(key: String): String? =
        primitive(key)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        primitive(key)?.longOrNull

    private fun JsonObject.int(key: String): Int? =
        primitive(key)?.intOrNull

    private fun JsonObject.double(key: String): Double? =
        primitive(key)?.doubleOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        primitive(key)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
}
