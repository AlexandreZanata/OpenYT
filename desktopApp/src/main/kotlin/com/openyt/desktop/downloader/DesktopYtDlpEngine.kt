package com.openyt.desktop.downloader

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaKind
import com.openyt.engine.ytdlp.YtDlpErrorMapper
import com.openyt.engine.ytdlp.YtDlpDownloadOptions
import com.openyt.engine.ytdlp.YtDlpJsonParser
import com.openyt.engine.ytdlp.YtDlpOutputParser
import com.openyt.engine.ytdlp.YtDlpProgressParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Desktop implementation that drives an external yt-dlp executable.
 *
 * Arguments are always passed as a list. No shell is involved.
 */
class DesktopYtDlpEngine(
    private val locator: ExecutableLocator = ExecutableLocator(),
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : DownloadEngine {

    private val processes = ConcurrentHashMap<String, Process>()
    private val canceled = ConcurrentHashMap.newKeySet<String>()
    private val paused = ConcurrentHashMap.newKeySet<String>()

    override suspend fun inspect(url: String): EngineResult<Inspection> = withContext(Dispatchers.IO) {
        val binary = locator.findYtDlp()
            ?: return@withContext EngineResult.Failure(DownloadFailure.DependencyMissing("yt-dlp"))

        val arguments = buildList {
            add(binary)
            add("--ignore-config")
            add("--encoding")
            add("utf-8")
            add("--dump-single-json")
            add("--no-warnings")
            add("--flat-playlist")
            add("--skip-download")
            locator.findJsRuntimeArgument()?.let {
                add("--js-runtimes")
                add(it)
            }
            add(url)
        }
        val output = runCapture(arguments)
        if (output.exitCode != 0) {
            EngineResult.Failure(YtDlpErrorMapper.fromToolOutput(output.stderr))
        } else {
            YtDlpJsonParser.parseInspection(output.stdout)
        }
    }

    override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> =
        channelFlow {
            if (request.kind == MediaKind.SUBTITLE && request.options.subtitle == null) {
                send(DownloadEvent.Failed(DownloadFailure.UnsupportedSource))
                return@channelFlow
            }

            val binary = locator.findYtDlp()
            if (binary == null) {
                send(DownloadEvent.Failed(DownloadFailure.DependencyMissing("yt-dlp")))
                return@channelFlow
            }
            if (paused.remove(downloadId)) {
                send(DownloadEvent.Paused)
                return@channelFlow
            }
            if (canceled.remove(downloadId)) {
                send(DownloadEvent.Canceled)
                return@channelFlow
            }

            val directory = File(request.destinationDirectory).apply { mkdirs() }
            val safeId = downloadId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(MAX_ID_LENGTH)
                .ifEmpty { "download" }
            val tempDirectory = File(directory, ".openyt-tmp-$safeId").apply { mkdirs() }
            val arguments = buildList {
                add(binary)
                add("--ignore-config")
                add("--encoding")
                add("utf-8")
                add("--no-colors")
                request.playlistItemIndex?.let {
                    add("--playlist-items")
                    add(it.toString())
                } ?: add("--no-playlist")
                add("--newline")
                add("--no-quiet")
                add("-P")
                add(directory.absolutePath)
                add("--paths")
                add("temp:${tempDirectory.absolutePath}")
                add("-o")
                add(request.fileNameTemplate)
                add("--print")
                add(if (request.kind == MediaKind.SUBTITLE) YtDlpOutputParser.SUBTITLE_PATH_TEMPLATE else YtDlpOutputParser.FILE_PATH_TEMPLATE)
                add("--progress-template")
                add(PROGRESS_TEMPLATE)
                addAll(YtDlpDownloadOptions.arguments(request))
                request.limitBytesPerSecond?.let {
                    add("--limit-rate")
                    add(it.toString())
                }
                request.cookiesFilePath?.let {
                    add("--cookies")
                    add(it)
                }
                locator.findFfmpeg()?.let {
                    add("--ffmpeg-location")
                    add(it)
                }
                locator.findJsRuntimeArgument()?.let {
                    add("--js-runtimes")
                    add(it)
                }
                add(request.url)
            }

            val process = try {
                startProcess(arguments)
            } catch (e: Exception) {
                tempDirectory.deleteRecursively()
                send(DownloadEvent.Failed(DownloadFailure.Unexpected(null)))
                return@channelFlow
            }

            processes[downloadId] = process
            if (downloadId in paused || downloadId in canceled) killTree(process)
            send(DownloadEvent.Starting)

            val stderrLines = Collections.synchronizedList(mutableListOf<String>())
            val stderrThread = thread(isDaemon = true, name = "openyt-stderr-$downloadId") {
                runCatching {
                    process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (stderrLines.size < MAX_STDERR_LINES) stderrLines.add(line)
                    }
                }
            }

            var finalPath: String? = null
            var pausedByUser = false
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    YtDlpProgressParser.parse(line)?.let { trySend(it) }
                    if (line.contains("[Merger]") || line.contains("[ExtractAudio]") ||
                        line.contains("[Fixup")
                    ) {
                        trySend(DownloadEvent.Processing)
                    }
                    YtDlpOutputParser.extractFilePath(line)?.let { finalPath = it }
                }

                val exitCode = process.waitFor()
                stderrThread.join()

                pausedByUser = paused.remove(downloadId)
                when {
                    pausedByUser -> send(DownloadEvent.Paused)

                    canceled.remove(downloadId) -> send(DownloadEvent.Canceled)

                    exitCode == 0 -> {
                        val file = com.openyt.data.confirmedEngineOutput(finalPath, directory, tempDirectory, request.kind == MediaKind.SUBTITLE)
                        if (file != null) {
                            send(
                                DownloadEvent.Completed(
                                    filePath = file.absolutePath,
                                    sizeBytes = file.length(),
                                ),
                            )
                        } else {
                            send(
                                DownloadEvent.Failed(
                                    DownloadFailure.OutputMissing,
                                ),
                            )
                        }
                    }

                    else -> send(
                        DownloadEvent.Failed(
                            YtDlpErrorMapper.fromToolOutput(stderrLines.joinToString("\n")),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pausedByUser = paused.remove(downloadId)
                val event = when {
                    pausedByUser -> DownloadEvent.Paused
                    canceled.remove(downloadId) -> DownloadEvent.Canceled
                    else -> DownloadEvent.Failed(DownloadFailure.Unexpected(null))
                }
                send(event)
            } finally {
                processes.remove(downloadId)
                canceled.remove(downloadId)
                if (process.isAlive) killTree(process)
                if (!pausedByUser) tempDirectory.deleteRecursively()
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun cancel(downloadId: String) {
        canceled.add(downloadId)
        processes.remove(downloadId)?.let(::killTree)
    }

    override suspend fun pause(downloadId: String) {
        paused.add(downloadId)
        processes.remove(downloadId)?.let(::killTree)
    }

    override suspend fun supportedSources(): List<String> = withContext(Dispatchers.IO) {
        val binary = locator.findYtDlp() ?: return@withContext emptyList()
        val output = runCapture(listOf(binary, "--ignore-config", "--encoding", "utf-8", "--list-extractors"))
        if (output.exitCode == 0) output.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted().toList() else emptyList()
    }

    override suspend fun capabilities(): EngineCapabilities = withContext(Dispatchers.IO) {
        val binary = locator.findYtDlp()
        val version = binary
            ?.let { runCapture(listOf(it, "--version")) }
            ?.takeIf { it.exitCode == 0 }
            ?.stdout
            ?.trim()
            ?.ifBlank { null }

        EngineCapabilities(
            engineName = "yt-dlp (desktop)",
            engineVersion = version,
            supportsPause = true,
            supportsSubtitles = true,
        )
    }

    override fun close() {
        processes.values.forEach(::killTree)
        processes.clear()
    }

    private fun runCapture(arguments: List<String>): ProcessOutput {
        val process = startProcess(arguments)
        val errorBuffer = ByteArrayOutputStream()
        val errorThread = thread(isDaemon = true) {
            runCatching { process.errorStream.copyTo(errorBuffer) }
        }
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val exitCode = process.waitFor()
        errorThread.join()
        return ProcessOutput(exitCode, stdout, errorBuffer.toString(Charsets.UTF_8))
    }

    private fun killTree(process: Process) {
        runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    private data class ProcessOutput(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_STDERR_LINES = 200
        const val PROGRESS_TEMPLATE =
            "download:OPENYT_PROGRESS:%(progress._percent_str)s|" +
                "%(progress.downloaded_bytes)s|" +
                "%(progress.total_bytes_estimate)s|" +
                "%(progress.speed)s|" +
                "%(progress.eta)s"
    }
}
