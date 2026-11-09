package com.openyt.core.testing

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaFormat
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
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
        emit(DownloadEvent.Completed(filePath = "/tmp/openyt/$downloadId.bin", sizeBytes = 1024))
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
            author = "OpenYT",
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
