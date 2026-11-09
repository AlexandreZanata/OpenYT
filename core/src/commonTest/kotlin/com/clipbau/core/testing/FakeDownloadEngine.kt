package com.clipbau.core.testing

import com.clipbau.core.domain.DownloadEngine
import com.clipbau.core.domain.DownloadEvent
import com.clipbau.core.domain.DownloadFailure
import com.clipbau.core.domain.DownloadRequest
import com.clipbau.core.domain.EngineCapabilities
import com.clipbau.core.domain.EngineResult
import com.clipbau.core.domain.MediaFormat
import com.clipbau.core.domain.MediaInfo
import com.clipbau.core.domain.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeDownloadEngine(
    var inspection: EngineResult<MediaInfo> = EngineResult.Success(SAMPLE_MEDIA),
    var capabilities: EngineCapabilities = EngineCapabilities(
        engineName = "fake",
        engineVersion = "0.0.0",
        supportsPause = false,
        supportsSubtitles = true,
    ),
) : DownloadEngine {
    var inspectCalls: Int = 0
        private set
    var canceledIds: MutableList<String> = mutableListOf()
        private set

    override suspend fun inspect(url: String): EngineResult<MediaInfo> {
        inspectCalls++
        return inspection
    }

    override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Starting)
        emit(DownloadEvent.Progress(percent = 50.0, downloadedBytes = 512, totalBytes = 1024))
        emit(DownloadEvent.Completed(filePath = "/tmp/clipbau/$downloadId.bin", sizeBytes = 1024))
    }

    override suspend fun cancel(downloadId: String) {
        canceledIds += downloadId
    }

    override suspend fun capabilities(): EngineCapabilities = capabilities

    override fun close() = Unit

    companion object {
        val SAMPLE_MEDIA = MediaInfo(
            sourceId = "sample-id",
            source = "sample",
            title = "Sample media",
            author = "ClipBaú",
            durationSeconds = 42,
            webpageUrl = "https://example.com/watch/sample-id",
            formats = listOf(
                MediaFormat(
                    id = "sample-video",
                    kind = MediaKind.VIDEO,
                    container = "mp4",
                    videoCodec = "h264",
                    audioCodec = "aac",
                    width = 1280,
                    height = 720,
                    sizeBytes = 1024,
                ),
            ),
        )
    }
}

fun unsupportedFailure(): DownloadFailure = DownloadFailure.UnsupportedSource
