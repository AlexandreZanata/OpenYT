package com.openyt.android.probe

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.openyt.core.application.MediaProbe
import com.openyt.core.domain.MediaTechnicalInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaProbe : MediaProbe {
    override suspend fun probe(path: String): MediaTechnicalInfo? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext null

        val extractor = MediaExtractor()
        var retriever: MediaMetadataRetriever? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var width: Int? = null
            var height: Int? = null
            var videoCodec: String? = null
            var audioCodec: String? = null
            var bitrate: Int? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> {
                        width = format.intOrNull(MediaFormat.KEY_WIDTH)
                        height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                        videoCodec = mime.substringAfter('/')
                        bitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE)?.div(1000) ?: bitrate
                    }

                    mime.startsWith("audio/") -> {
                        audioCodec = mime.substringAfter('/')
                        if (bitrate == null) {
                            bitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE)?.div(1000)
                        }
                    }
                }
            }

            retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull()

            MediaTechnicalInfo(
                durationMillis = duration,
                width = width,
                height = height,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                bitrateKbps = bitrate,
                container = file.extension.takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
            runCatching { retriever?.release() }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}
