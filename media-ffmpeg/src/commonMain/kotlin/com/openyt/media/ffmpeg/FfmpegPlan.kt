package com.openyt.media.ffmpeg

import com.openyt.core.application.MediaOperation
import com.openyt.core.application.MediaProcessingRequest
import com.openyt.core.application.ClipFraming

object FfmpegPlan {
    fun arguments(request: MediaProcessingRequest, output: String): List<String> = buildList {
        addAll(listOf("-hide_banner", "-loglevel", "error", "-n"))
        if (request.operation == MediaOperation.TRIM || request.operation == MediaOperation.RENDER_CLIP) {
            val segment = requireNotNull(request.segment) { "missing trim interval" }
            addAll(listOf("-ss", (segment.startMillis / 1000.0).toString()))
        }
        addAll(listOf("-i", request.sourcePath))
        when (request.operation) {
            MediaOperation.RENDER_CLIP -> {
                val segment = requireNotNull(request.segment)
                addAll(listOf("-t", ((segment.endMillis - segment.startMillis) / 1000.0).toString(),
                    "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "fast", "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart"))
                val filters = buildList {
                    // Normalize sample aspect ratio before fitting/cropping. Merely
                    // assigning SAR=1 squeezes anamorphic sources horizontally.
                    add("scale=trunc(iw*sar/2)*2:trunc(ih/2)*2,setsar=1")
                    when (request.framing) {
                        ClipFraming.ORIGINAL -> Unit
                        ClipFraming.FIT_PORTRAIT -> add("scale=720:1280:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=720:1280:(ow-iw)/2:(oh-ih)/2,setsar=1")
                        ClipFraming.CENTER_CROP -> add("scale=720:1280:force_original_aspect_ratio=increase:force_divisible_by=2,crop=720:1280,setsar=1")
                    }
                    if (request.captions.isNotEmpty()) add("subtitles=captions.srt")
                }
                addAll(listOf("-vf", filters.joinToString(",")))
            }
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
