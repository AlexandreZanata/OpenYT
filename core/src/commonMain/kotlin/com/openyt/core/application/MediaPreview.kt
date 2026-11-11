package com.openyt.core.application

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaSegment
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface MediaPreview {
    suspend fun preview(media: MediaInfo, url: String, segment: MediaSegment): EngineResult<Unit>
}

class EngineMediaPreview(
    private val engine: DownloadEngine,
    private val launcher: PlaybackLauncher,
    private val cacheDirectory: String,
    private val nextId: () -> String,
) : MediaPreview {
    override suspend fun preview(media: MediaInfo, url: String, segment: MediaSegment): EngineResult<Unit> = coroutineScope {
        val id = nextId()
        val fileToken = id.filter { it.isLetterOrDigit() || it in "-_" }.take(64)
        val finished = MutableStateFlow(false)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally {
                if (!finished.value) withContext(NonCancellable) { runCatching { engine.cancel(id) } }
            }
        }
        var result: EngineResult<Unit> = EngineResult.Failure(DownloadFailure.OutputMissing)
        try {
            engine.download(DownloadRequest(
                url = url,
                destinationDirectory = cacheDirectory,
                fileNameTemplate = "preview-$fileToken-%(id)s.%(ext)s",
                options = DownloadOptions(segment = segment),
            ), id).collect { event ->
                when (event) {
                    is DownloadEvent.Completed -> {
                        finished.value = true
                        launcher.play(listOf(PlayableMedia(event.filePath, media.title, com.openyt.core.domain.MediaKind.VIDEO)), 0)
                        result = EngineResult.Success(Unit)
                    }
                    is DownloadEvent.Failed -> { finished.value = true; result = EngineResult.Failure(event.failure) }
                    else -> Unit
                }
            }
            finished.value = true
        } finally {
            cancellation.cancel()
        }
        result
    }
}
