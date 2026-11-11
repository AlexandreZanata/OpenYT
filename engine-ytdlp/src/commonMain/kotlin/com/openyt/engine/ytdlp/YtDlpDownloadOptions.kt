package com.openyt.engine.ytdlp

import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.MediaKind

/** Argument values are owned here and reach platform processes as separate list entries. */
object YtDlpDownloadOptions {
    fun arguments(request: DownloadRequest): List<String> = buildList {
        when (request.kind) {
            MediaKind.AUDIO -> {
                addAll(listOf("-f", request.formatId ?: "bestaudio/best", "-x", "--audio-format", request.options.audioPreset.extension))
            }
            MediaKind.VIDEO -> addAll(listOf("-f", request.formatId ?: "bv*+ba/b"))
            MediaKind.SUBTITLE -> add("--skip-download")
        }
        val subtitle = request.options.subtitle
        if (subtitle != null) {
            require(Regex("^[A-Za-z0-9_-]{1,32}$").matches(subtitle.language)) { "invalid subtitle language" }
            add(if (subtitle.automatic) "--write-auto-subs" else "--write-subs")
            addAll(listOf("--sub-langs", subtitle.language, "--sub-format", "srt/vtt/best", "--convert-subs", "srt"))
            if (subtitle.embed && request.kind == MediaKind.VIDEO) add("--embed-subs")
        }
        if (request.options.preserveChapters && request.kind != MediaKind.SUBTITLE) add("--embed-chapters")
        if (request.options.normalizeAudio && request.kind == MediaKind.AUDIO) {
            add("--add-metadata")
            addAll(listOf("--postprocessor-args", "Metadata+ffmpeg_o:-c:a ${request.options.audioPreset.codec} -af loudnorm=I=-16:LRA=11:TP=-1.5"))
        }
        if (request.options.sponsorBlockMarkers) addAll(listOf("--sponsorblock-mark", "sponsor,selfpromo"))
        request.options.segment?.let { segment ->
            addAll(listOf("--download-sections", "*${segment.startMillis / 1000.0}-${segment.endMillis / 1000.0}"))
        }
        request.options.liveDurationSeconds?.let { seconds ->
            require(seconds in 1..86_400) { "invalid capture duration" }
            addAll(listOf("--downloader", "ffmpeg", "--downloader-args", "ffmpeg_o:-t $seconds"))
        }
    }
}
