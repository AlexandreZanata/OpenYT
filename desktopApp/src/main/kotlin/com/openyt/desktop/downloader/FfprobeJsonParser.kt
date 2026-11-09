package com.openyt.desktop.downloader

import com.openyt.core.domain.MediaTechnicalInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object FfprobeJsonParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): MediaTechnicalInfo? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        val streams = (root["streams"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val video = streams.firstOrNull { it.string("codec_type") == "video" }
        val audio = streams.firstOrNull { it.string("codec_type") == "audio" }
        val format = root["format"] as? JsonObject
        if (streams.isEmpty() && format == null) return null

        val durationSeconds = format?.string("duration")?.toDoubleOrNull()
        val bitRate = format?.string("bit_rate")?.toLongOrNull()
            ?: video?.string("bit_rate")?.toLongOrNull()

        return MediaTechnicalInfo(
            durationMillis = durationSeconds?.let { (it * 1000).toLong() },
            width = video?.string("width")?.toIntOrNull(),
            height = video?.string("height")?.toIntOrNull(),
            videoCodec = video?.string("codec_name"),
            audioCodec = audio?.string("codec_name"),
            bitrateKbps = bitRate?.div(1000)?.toInt(),
            container = format?.string("format_name"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
