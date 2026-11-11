package com.openyt.media.ffmpeg

import com.openyt.core.application.MediaOperation
import com.openyt.core.application.MediaProcessingRequest

object FfmpegPlan {
    fun arguments(request: MediaProcessingRequest, output: String): List<String> = buildList {
        addAll(listOf("-hide_banner", "-loglevel", "error", "-n"))
        if (request.operation == MediaOperation.TRIM) {
            val segment = requireNotNull(request.segment) { "missing trim interval" }
            addAll(listOf("-ss", (segment.startMillis / 1000.0).toString()))
        }
        addAll(listOf("-i", request.sourcePath))
        when (request.operation) {
            MediaOperation.TRIM -> {
                val segment = requireNotNull(request.segment)
                addAll(listOf("-t", ((segment.endMillis - segment.startMillis) / 1000.0).toString(), "-map", "0", "-c", "copy", "-avoid_negative_ts", "make_zero"))
            }
            MediaOperation.CONVERT_AUDIO -> {
                addAll(listOf("-vn", "-c:a", request.audioPreset.codec))
                if (request.normalize) addAll(listOf("-af", "loudnorm=I=-16:LRA=11:TP=-1.5"))
            }
        }
        add(output)
    }
}
