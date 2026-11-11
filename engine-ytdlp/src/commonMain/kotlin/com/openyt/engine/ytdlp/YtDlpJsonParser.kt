package com.openyt.engine.ytdlp

import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaFormat
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.SubtitleTrack
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.PlaylistEntry
import com.openyt.core.domain.PlaylistInfo
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

    fun parseInspection(raw: String): EngineResult<Inspection> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return EngineResult.Failure(DownloadFailure.ProcessingFailed("unreadable metadata"))
        val obj = root as? JsonObject
            ?: return EngineResult.Failure(DownloadFailure.ProcessingFailed("unexpected metadata shape"))

        return if (obj.string("_type") == "playlist" || obj.string("_type") == "multi_video") {
            parsePlaylist(obj)
        } else {
            parseSingle(obj)
        }
    }

    private fun parseSingle(obj: JsonObject): EngineResult<Inspection> {
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
            Inspection.Single(
                MediaInfo(
                    sourceId = id,
                    source = extractor(obj),
                    title = title,
                    author = obj.string("uploader") ?: obj.string("channel") ?: obj.string("creator"),
                    durationSeconds = obj.long("duration"),
                    thumbnailUrl = obj.string("thumbnail") ?: firstThumbnail(obj),
                    webpageUrl = webpageUrl,
                    formats = formats,
                    isLive = obj.boolean("is_live") == true,
                    subtitles = listOf("subtitles" to false, "automatic_captions" to true).flatMap { (key, automatic) ->
                        (obj[key] as? JsonObject)?.mapNotNull { (language, values) ->
                            val tracks = values as? JsonArray
                            if (language == "live_chat" || !Regex("^[A-Za-z0-9_-]{1,32}$").matches(language) ||
                                tracks.orEmpty().none { (it as? JsonObject)?.string("ext") in setOf("srt", "vtt", "ttml", "srv1", "srv2", "srv3", "json3", "ass", "lrc") }) null
                            else SubtitleTrack(language, automatic = automatic)
                        }.orEmpty()
                    },
                ),
            ),
        )
    }

    private fun parsePlaylist(obj: JsonObject): EngineResult<Inspection> {
        val entries = (obj["entries"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapIndexedNotNull { index, entry ->
                val url = entry.string("webpage_url")
                    ?: entry.string("url")
                    ?: entry.string("original_url")
                    ?: return@mapIndexedNotNull null
                PlaylistEntry(
                    index = index + 1,
                    title = entry.string("title") ?: url,
                    url = url,
                    author = entry.string("uploader")
                        ?: entry.string("channel")
                        ?: entry.string("creator"),
                    durationSeconds = entry.long("duration"),
                    thumbnailUrl = entry.string("thumbnail") ?: firstThumbnail(entry),
                )
            }
            .orEmpty()
        if (entries.isEmpty()) {
            return EngineResult.Failure(
                DownloadFailure.ProcessingFailed("playlist has no downloadable entries"),
            )
        }

        val title = obj.string("title") ?: obj.string("fulltitle") ?: "Playlist"
        return EngineResult.Success(
            Inspection.Playlist(
                PlaylistInfo(
                    sourceId = obj.string("id") ?: title,
                    source = extractor(obj),
                    title = title,
                    author = obj.string("uploader") ?: obj.string("channel") ?: obj.string("creator"),
                    thumbnailUrl = obj.string("thumbnail")
                        ?: firstThumbnail(obj)
                        ?: entries.firstNotNullOfOrNull { it.thumbnailUrl },
                    webpageUrl = obj.string("webpage_url") ?: obj.string("original_url").orEmpty(),
                    entries = entries,
                ),
            ),
        )
    }

    private fun extractor(obj: JsonObject): String =
        (obj.string("extractor") ?: obj.string("extractor_key") ?: "unknown").lowercase()

    private fun firstThumbnail(obj: JsonObject): String? =
        (obj["thumbnails"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { it.string("url") }
            ?.lastOrNull()

    private fun format(obj: JsonObject): MediaFormat? {
        val formatId = obj.string("format_id") ?: return null
        val container = obj.string("ext") ?: "unknown"
        val videoCodec = obj.string("vcodec")
        val audioCodec = obj.string("acodec")
        val hasVideo = !videoCodec.isNullOrBlank() && videoCodec != "none"
        val hasAudio = !audioCodec.isNullOrBlank() && audioCodec != "none"
        val width = obj.int("width")
        val height = obj.int("height")

        val kind = when {
            hasVideo -> MediaKind.VIDEO
            hasAudio -> MediaKind.AUDIO
            (width ?: 0) > 0 || (height ?: 0) > 0 -> MediaKind.VIDEO
            container in AUDIO_CONTAINERS -> MediaKind.AUDIO
            else -> MediaKind.VIDEO
        }

        return MediaFormat(
            id = formatId,
            kind = kind,
            container = container,
            videoCodec = videoCodec.takeUnless { it == "none" },
            audioCodec = audioCodec.takeUnless { it == "none" },
            width = width,
            height = height,
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

    private val AUDIO_CONTAINERS = setOf("m4a", "mp3", "opus", "ogg", "oga", "wav", "flac", "aac")
}
