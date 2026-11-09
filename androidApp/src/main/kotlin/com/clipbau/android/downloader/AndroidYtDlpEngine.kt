package com.clipbau.android.downloader

import android.content.Context
import com.clipbau.core.domain.DownloadEngine
import com.clipbau.core.domain.DownloadEvent
import com.clipbau.core.domain.DownloadFailure
import com.clipbau.core.domain.DownloadRequest
import com.clipbau.core.domain.EngineCapabilities
import com.clipbau.core.domain.EngineResult
import com.clipbau.core.domain.MediaInfo
import com.clipbau.core.domain.MediaKind
import com.clipbau.engine.ytdlp.YtDlpErrorMapper
import com.clipbau.engine.ytdlp.YtDlpJsonParser
import com.clipbau.engine.ytdlp.YtDlpOutputParser
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android implementation backed by youtubedl-android.
 *
 * The wrapper bundles Python, yt-dlp, FFmpeg, and QuickJS, and injects the JS
 * runtime and FFmpeg location itself. This class only maps requests and events; it
 * never builds shell commands and never exposes raw tool output to callers.
 */
class AndroidYtDlpEngine(
    private val context: Context,
) : DownloadEngine {

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    override suspend fun inspect(url: String): EngineResult<MediaInfo> {
        val initFailure = initialize()
        if (initFailure != null) return EngineResult.Failure(initFailure)

        return withContext(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("--dump-single-json")
                    addOption("--no-playlist")
                    addOption("--skip-download")
                }
                val response = YoutubeDL.getInstance().execute(request)
                YtDlpJsonParser.parseInspection(response.out)
            } catch (e: YoutubeDL.CanceledException) {
                EngineResult.Failure(DownloadFailure.Unexpected("inspection canceled"))
            } catch (e: Exception) {
                EngineResult.Failure(YtDlpErrorMapper.fromToolOutput(e.message.orEmpty()))
            }
        }
    }

    override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> =
        channelFlow {
            val initFailure = initialize()
            if (initFailure != null) {
                send(DownloadEvent.Failed(initFailure))
                return@channelFlow
            }
            if (request.kind == MediaKind.SUBTITLE) {
                send(DownloadEvent.Failed(DownloadFailure.UnsupportedSource))
                return@channelFlow
            }

            val directory = File(request.destinationDirectory).apply { mkdirs() }
            send(DownloadEvent.Starting)

            try {
                val response = withContext(Dispatchers.IO) {
                    val ytDlpRequest = YoutubeDLRequest(request.url).apply {
                        addOption("--no-playlist")
                        addOption("--newline")
                        addOption("-P", directory.absolutePath)
                        addOption("-o", request.fileNameTemplate)
                        addOption("--print", "after_move:filepath")
                        when (request.kind) {
                            MediaKind.AUDIO -> {
                                addOption("-f", request.formatId ?: "bestaudio/best")
                                addOption("-x")
                                addOption("--audio-format", "m4a")
                            }

                            MediaKind.VIDEO ->
                                addOption("-f", request.formatId ?: "bv*+ba/b")

                            MediaKind.SUBTITLE -> Unit
                        }
                    }
                    YoutubeDL.getInstance().execute(ytDlpRequest, downloadId) { progress, eta, line ->
                        if (progress in 0f..100f) {
                            trySend(
                                DownloadEvent.Progress(
                                    percent = progress.toDouble(),
                                    etaSeconds = eta.takeIf { it > 0 },
                                ),
                            )
                        }
                        if (line.contains("[Merger]") || line.contains("[ExtractAudio]")) {
                            trySend(DownloadEvent.Processing)
                        }
                    }
                }

                val path = YtDlpOutputParser.extractFilePath(response.out)
                if (path != null) {
                    send(DownloadEvent.Completed(path, File(path).length().takeIf { it > 0 }))
                } else {
                    send(DownloadEvent.Failed(DownloadFailure.ProcessingFailed(null)))
                }
            } catch (e: YoutubeDL.CanceledException) {
                send(DownloadEvent.Canceled)
            } catch (e: Exception) {
                send(DownloadEvent.Failed(YtDlpErrorMapper.fromToolOutput(e.message.orEmpty())))
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun cancel(downloadId: String) {
        YoutubeDL.getInstance().destroyProcessById(downloadId)
    }

    override suspend fun capabilities(): EngineCapabilities {
        val version = runCatching {
            initialize()
            withContext(Dispatchers.IO) { YoutubeDL.getInstance().version(context) }
        }.getOrNull()

        return EngineCapabilities(
            engineName = "yt-dlp (Android)",
            engineVersion = version,
            supportsPause = false,
            supportsSubtitles = false,
        )
    }

    override fun close() = Unit

    private suspend fun initialize(): DownloadFailure? {
        if (initialized) return null
        return initMutex.withLock {
            if (initialized) return@withLock null
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(context.applicationContext)
                }
                initialized = true
                null
            } catch (e: Exception) {
                DownloadFailure.DependencyMissing("yt-dlp runtime")
            }
        }
    }
}
