package com.openyt.android.downloader

import android.content.Context
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaKind
import com.openyt.engine.ytdlp.YtDlpErrorMapper
import com.openyt.engine.ytdlp.YtDlpJsonParser
import com.openyt.engine.ytdlp.YtDlpOutputParser
import com.openyt.engine.ytdlp.YtDlpProgressParser
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    private val paused = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var initialized = false

    override suspend fun inspect(url: String): EngineResult<Inspection> {
        val initFailure = initialize()
        if (initFailure != null) return EngineResult.Failure(initFailure)

        return withContext(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("--dump-single-json")
                    addOption("--flat-playlist")
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
            if (paused.remove(downloadId)) {
                send(DownloadEvent.Paused)
                return@channelFlow
            }

            val directory = File(request.destinationDirectory).apply { mkdirs() }
            val safeId = downloadId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(MAX_ID_LENGTH)
                .ifEmpty { "download" }
            val tempDirectory = File(directory, ".openyt-tmp-$safeId").apply { mkdirs() }
            send(DownloadEvent.Starting)

            var pausedByUser = false
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

                val file = YtDlpOutputParser.extractFilePath(response.out)
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0 }
                if (file != null) {
                    send(DownloadEvent.Completed(file.absolutePath, file.length()))
                } else {
                    send(
                        DownloadEvent.Failed(
                            DownloadFailure.ProcessingFailed("output file missing or empty"),
                        ),
                    )
                }
            } catch (e: YoutubeDL.CanceledException) {
                pausedByUser = paused.remove(downloadId)
                if (pausedByUser) {
                    send(DownloadEvent.Paused)
                } else {
                    send(DownloadEvent.Canceled)
                }
            } catch (e: Exception) {
                if (paused.remove(downloadId)) {
                    pausedByUser = true
                    send(DownloadEvent.Paused)
                } else {
                    send(DownloadEvent.Failed(YtDlpErrorMapper.fromToolOutput(e.message.orEmpty())))
                }
            } finally {
                if (!pausedByUser) tempDirectory.deleteRecursively()
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun cancel(downloadId: String) {
        YoutubeDL.getInstance().destroyProcessById(downloadId)
    }

    override suspend fun pause(downloadId: String) {
        paused.add(downloadId)
        YoutubeDL.getInstance().destroyProcessById(downloadId)
    }

    override suspend fun capabilities(): EngineCapabilities {
        val version = runCatching {
            initialize()
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().version(context) ?: runCatching {
                    val request = YoutubeDLRequest("").apply { addOption("--version") }
                    YoutubeDL.getInstance().execute(request).out
                        .trim()
                        .lineSequence()
                        .lastOrNull { it.isNotBlank() }
                }.getOrNull()
            }
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
            "download:OPENYT_PROGRESS:%(progress._percent_str)s|" +
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
