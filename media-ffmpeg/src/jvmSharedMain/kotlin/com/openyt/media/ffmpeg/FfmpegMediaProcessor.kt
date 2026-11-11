package com.openyt.media.ffmpeg

import com.openyt.core.application.MediaOperation
import com.openyt.core.application.MediaProcessingRequest
import com.openyt.core.application.MediaProcessor
import com.openyt.core.application.ProcessedMedia
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaKind
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Creates a new file atomically. The input is never overwritten or deleted. */
class FfmpegMediaProcessor(
    private val executable: suspend () -> String?,
    private val processEnvironment: () -> Map<String, String> = { emptyMap() },
) : MediaProcessor {
    override suspend fun process(request: MediaProcessingRequest): EngineResult<ProcessedMedia> = withContext(Dispatchers.IO) {
        val binary = executable() ?: return@withContext EngineResult.Failure(DownloadFailure.DependencyMissing("ffmpeg"))
        val source = File(request.sourcePath)
        if (!source.isFile) return@withContext EngineResult.Failure(DownloadFailure.Unavailable(null))
        val id = UUID.randomUUID().toString()
        val extension = if (request.operation == MediaOperation.TRIM) source.extension else request.audioPreset.extension
        val suffix = if (request.operation == MediaOperation.TRIM) "clip" else request.audioPreset.extension
        val output = File(source.parentFile, "${source.nameWithoutExtension.take(80)}-$suffix-${id.take(8)}.$extension")
        val temporary = File(source.parentFile, ".openyt-process-$id.$extension")
        var process: Process? = null
        try {
            val arguments = listOf(binary) + FfmpegPlan.arguments(request, temporary.absolutePath)
            process = ProcessBuilder(arguments).redirectErrorStream(true).apply {
                environment().putAll(processEnvironment())
            }.start()
            val running = process
            val reader = thread(isDaemon = true) {
                runCatching { running.inputStream.bufferedReader().forEachLine {} }
            }
            val ended = runInterruptible { running.waitFor(15, TimeUnit.MINUTES) }
            if (!ended || running.exitValue() != 0 || !temporary.isFile || temporary.length() == 0L) {
                return@withContext EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
            }
            reader.join(1_000)
            Files.move(temporary.toPath(), output.toPath())
            EngineResult.Success(ProcessedMedia(
                id = "processed-$id",
                title = "${request.title} ($suffix)",
                path = output.absolutePath,
                kind = if (request.operation == MediaOperation.CONVERT_AUDIO) MediaKind.AUDIO else request.kind,
                sizeBytes = output.length(),
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            EngineResult.Failure(DownloadFailure.PermissionDenied)
        } catch (e: Exception) {
            EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
            temporary.delete()
        }
    }
}
