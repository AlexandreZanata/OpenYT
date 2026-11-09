package com.clipbau.desktop.downloader

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
) : DownloadEngine {

    private val processes = ConcurrentHashMap<String, Process>()
    private val canceled = ConcurrentHashMap.newKeySet<String>()

    override suspend fun inspect(url: String): EngineResult<MediaInfo> = withContext(Dispatchers.IO) {
        val binary = locator.findYtDlp()
            ?: return@withContext EngineResult.Failure(DownloadFailure.DependencyMissing("yt-dlp"))

        val arguments = buildList {
            add(binary)
            add("--dump-single-json")
            add("--no-warnings")
            add("--no-playlist")
            add("--skip-download")
            locator.findJsRuntime()?.let {
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
            if (request.kind == MediaKind.SUBTITLE) {
                send(DownloadEvent.Failed(DownloadFailure.UnsupportedSource))
                return@channelFlow
            }

            val binary = locator.findYtDlp()
            if (binary == null) {
                send(DownloadEvent.Failed(DownloadFailure.DependencyMissing("yt-dlp")))
                return@channelFlow
            }

            val directory = File(request.destinationDirectory).apply { mkdirs() }
            val tempDirectory = File(directory, ".clipbau-tmp-$downloadId").apply { mkdirs() }
            val arguments = buildList {
                add(binary)
                add("--no-playlist")
                add("--newline")
                add("--no-quiet")
                add("-P")
                add(directory.absolutePath)
                add("--paths")
                add("temp:${tempDirectory.absolutePath}")
                add("-o")
                add(request.fileNameTemplate)
                add("--print")
                add("after_move:filepath")
                add("--progress-template")
                add(PROGRESS_TEMPLATE)
                when (request.kind) {
                    MediaKind.AUDIO -> {
                        add("-f")
                        add(request.formatId ?: "bestaudio/best")
                        add("-x")
                        add("--audio-format")
                        add("m4a")
                    }

                    MediaKind.VIDEO -> {
                        add("-f")
                        add(request.formatId ?: "bv*+ba/b")
                    }

                    MediaKind.SUBTITLE -> Unit
                }
                locator.findFfmpeg()?.let {
                    add("--ffmpeg-location")
                    add(it)
                }
                locator.findJsRuntime()?.let {
                    add("--js-runtimes")
                    add(it)
                }
                add(request.url)
            }

            val process = try {
                ProcessBuilder(arguments).start()
            } catch (e: Exception) {
                send(DownloadEvent.Failed(DownloadFailure.Unexpected(null)))
                return@channelFlow
            }

            processes[downloadId] = process
            send(DownloadEvent.Starting)

            val stderrLines = Collections.synchronizedList(mutableListOf<String>())
            val stderrThread = thread(isDaemon = true, name = "clipbau-stderr-$downloadId") {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        if (stderrLines.size < MAX_STDERR_LINES) stderrLines.add(line)
                    }
                }
            }

            var finalPath: String? = null
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    YtDlpProgressParser.parse(line)?.let { trySend(it) }
                    if (line.contains("[Merger]") || line.contains("[ExtractAudio]")) {
                        trySend(DownloadEvent.Processing)
                    }
                    YtDlpOutputParser.extractFilePath(line)?.let { finalPath = it }
                }

                val exitCode = process.waitFor()
                stderrThread.join()

                when {
                    canceled.remove(downloadId) -> send(DownloadEvent.Canceled)

                    exitCode == 0 -> {
                        val file = finalPath?.let(::File)?.takeIf { it.isFile }
                        send(
                            DownloadEvent.Completed(
                                filePath = file?.absolutePath ?: directory.absolutePath,
                                sizeBytes = file?.length(),
                            ),
                        )
                    }

                    else -> send(
                        DownloadEvent.Failed(
                            YtDlpErrorMapper.fromToolOutput(stderrLines.joinToString("\n")),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                processes.remove(downloadId)
                canceled.remove(downloadId)
                if (process.isAlive) killTree(process)
                tempDirectory.deleteRecursively()
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun cancel(downloadId: String) {
        canceled.add(downloadId)
        processes.remove(downloadId)?.let(::killTree)
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
            supportsPause = false,
            supportsSubtitles = false,
        )
    }

    override fun close() {
        processes.values.forEach(::killTree)
        processes.clear()
    }

    private fun runCapture(arguments: List<String>): ProcessOutput {
        val process = ProcessBuilder(arguments).start()
        val errorBuffer = ByteArrayOutputStream()
        val errorThread = thread(isDaemon = true) {
            runCatching { process.errorStream.copyTo(errorBuffer) }
        }
        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        errorThread.join()
        return ProcessOutput(exitCode, stdout, errorBuffer.toString())
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
        const val MAX_STDERR_LINES = 200
        const val PROGRESS_TEMPLATE =
            "download:CLIPBAU_PROGRESS:%(progress._percent_str)s|" +
                "%(progress.downloaded_bytes)s|" +
                "%(progress.total_bytes_estimate)s|" +
                "%(progress.speed)s|" +
                "%(progress.eta)s"
    }
}
