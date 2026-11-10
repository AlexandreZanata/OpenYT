package com.openyt.android.downloader

import com.openyt.core.application.AudioMetadata
import com.openyt.core.application.MediaTagger

/**
 * Metadata embedding is not implemented on Android yet. The bundled FFmpeg from
 * youtubedl-android is used internally by yt-dlp but does not expose a public
 * command runner, so the app reports the failure instead of pretending success.
 */
class AndroidMediaTagger : MediaTagger {
    override suspend fun writeTags(
        path: String,
        metadata: AudioMetadata,
        coverUrl: String?,
    ): Boolean = false
}
