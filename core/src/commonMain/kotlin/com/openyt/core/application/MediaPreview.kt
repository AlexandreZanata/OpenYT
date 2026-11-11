package com.openyt.core.application

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaSegment

interface MediaPreview {
    suspend fun preview(media: MediaInfo, url: String, segment: MediaSegment): EngineResult<Unit>
}

class EngineMediaPreview(
    private val engine: DownloadEngine,
    private val launcher: PlaybackLauncher,
    private val cacheDirectory: String,
    private val nextId: () -> String,
) : MediaPreview {
    override suspend fun preview(media: MediaInfo, url: String, segment: MediaSegment): EngineResult<Unit> {
        var result: EngineResult<Unit> = EngineResult.Failure(DownloadFailure.OutputMissing)
        engine.download(DownloadRequest(
            url = url,
            destinationDirectory = cacheDirectory,
            fileNameTemplate = "preview-%(id)s.%(ext)s",
            options = DownloadOptions(segment = segment),
        ), nextId()).collect { event ->
            when (event) {
                is DownloadEvent.Completed -> {
                    launcher.play(listOf(PlayableMedia(event.filePath, media.title, com.openyt.core.domain.MediaKind.VIDEO)), 0)
                    result = EngineResult.Success(Unit)
                }
                is DownloadEvent.Failed -> result = EngineResult.Failure(event.failure)
                else -> Unit
            }
        }
        return result
    }
}
