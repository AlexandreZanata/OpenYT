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
import com.clipbau.engine.ytdlp.YtDlpProgressParser
import com.yausername.ffmpeg.FFmpeg
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
            val safeId = downloadId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(MAX_ID_LENGTH)
                .ifEmpty { "download" }
            val tempDirectory = File(directory, ".clipbau-tmp-$safeId").apply { mkdirs() }
            send(DownloadEvent.Starting)

            try {
                val response = withContext(Dispatchers.IO) {
                    val ytDlpRequest = YoutubeDLRequest(request.url).apply {
                        addOption("--no-playlist")
                        addOption("--newline")
                        addOption("--no-quiet")
                        addOption("-P", directory.absolutePath)
                        addOption("--paths", "temp:${tempDirectory.absolutePath}")
                        addOption("-o", request.fileNameTemplate)
                        addOption("--print", "after_move:filepath")
                        addOption("--progress-template", PROGRESS_TEMPLATE)
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
                    YoutubeDL.getInstance().execute(ytDlpRequest, downloadId) { _, _, line ->
                        YtDlpProgressParser.parse(line)?.let { trySend(it) }
                        if (
                            line.contains("[Merger]") ||
                            line.contains("[ExtractAudio]") ||
                            line.contains("[FixupM4a]")
                        ) {
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
            } finally {
                tempDirectory.deleteRecursively()
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

    private companion object {
        const val MAX_ID_LENGTH = 64

        const val PROGRESS_TEMPLATE =
            "download:CLIPBAU_PROGRESS:%(progress._percent_str)s|" +
                "%(progress.downloaded_bytes)s|" +
                "%(progress.total_bytes_estimate)s|" +
                "%(progress.speed)s|" +
                "%(progress.eta)s"
    }

    private suspend fun initialize(): DownloadFailure? {
        if (initialized) return null
        return initMutex.withLock {
            if (initialized) return@withLock null
            try {
                withContext(Dispatchers.IO) {
                    FFmpeg.getInstance().init(context.applicationContext)
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
