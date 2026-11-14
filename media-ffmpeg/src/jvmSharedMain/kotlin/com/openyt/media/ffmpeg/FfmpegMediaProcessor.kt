package com.openyt.media.ffmpeg

import com.openyt.core.application.*
import com.openyt.core.domain.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** External FFmpeg only. Serial processing, durable per-clip receipts, no source overwrite. */
class FfmpegMediaProcessor(
    private val executable: suspend () -> String?,
    private val processEnvironment: () -> Map<String, String> = { emptyMap() },
    private val clipStore: ClipJobStore? = null,
    private val previewRoot: File? = null,
    private val onClipReady: suspend (ProcessedMedia) -> Unit = {},
    private val outputDirectory: () -> File? = { null },
) : MediaProcessor {
    private val lock = Mutex()
    private var initialized = false
    private var cachedCapabilities: MediaProcessingCapabilities? = null
    private val ownedPreviews = mutableSetOf<String>()
    override suspend fun clipDestination(): String? = withContext(Dispatchers.IO) { outputDirectory()?.absolutePath }

    override suspend fun previewClip(request: MediaProcessingRequest): EngineResult<ProcessedMedia> = lock.withLock {
        if (previewRoot == null || !request.validClip()) return@withLock EngineResult.Failure(DownloadFailure.DependencyMissing("clip preview"))
        withContext(Dispatchers.IO) { processOwned(request.copy(jobId = null), preview = true) }
    }

    override suspend fun releasePreview(output: ProcessedMedia) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (ownedPreviews.remove(output.path)) {
                File(output.path).delete()
                output.coverPath?.let { File(it).delete() }
            }
            Unit
        }
    }

    private suspend fun initialize() {
        if (!initialized) { clipStore?.interruptRunning(); initialized = true }
    }

    override suspend fun clipJobs(entryId: String): List<ClipJobRecord> = lock.withLock {
        initialize()
        clipStore?.load(entryId).orEmpty()
    }

    override suspend fun queueClips(requests: List<MediaProcessingRequest>): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        initialize()
        val store = clipStore ?: return@withLock false
        if (requests.isEmpty() || requests.size > 100 || requests.any { !it.validClip() || it.jobId == null } || requests.map { it.jobId }.distinct().size != requests.size) return@withLock false
        for (request in requests) {
            val old = store.get(request.jobId!!)
            if (old != null && old.request != request) return@withLock false
        }
        requests.forEach { request ->
            val old = store.get(request.jobId!!)
            if (old == null) {
                val source = File(request.sourcePath)
                if (!source.isFile) return@withLock false
                store.save(ClipJobRecord(request.jobId!!, request, sha256(source), ClipJobStatus.QUEUED))
            }
        }
        true
    } }

    override suspend fun cancelPendingClips(entryId: String) = lock.withLock {
        clipStore?.load(entryId)?.filter { it.status == ClipJobStatus.QUEUED }?.forEach { clipStore.save(it.copy(status = ClipJobStatus.CANCELED)) }
        Unit
    }

    override suspend fun capabilities(): MediaProcessingCapabilities = withContext(Dispatchers.IO) {
        cachedCapabilities?.let { return@withContext it }
        val binary = resolveExecutable() ?: return@withContext MediaProcessingCapabilities()
        val encoders = runTool(binary, listOf("-hide_banner", "-encoders"), null, 30).second
        val filters = runTool(binary, listOf("-hide_banner", "-filters"), null, 30).second
        fun has(text: String, name: String) = Regex("(?m)^\\s*\\S+\\s+${Regex.escape(name)}\\s").containsMatchIn(text)
        MediaProcessingCapabilities(
            preciseClips = has(encoders, "libx264") && has(encoders, "aac"),
            portrait = listOf("scale", "pad", "crop", "setsar").all { has(filters, it) },
            burnCaptions = has(filters, "subtitles"), covers = has(encoders, "png"), durableBatches = clipStore != null, previews = previewRoot != null,
        ).also { cachedCapabilities = it }
    }

    override suspend fun process(request: MediaProcessingRequest): EngineResult<ProcessedMedia> = lock.withLock {
        withContext(Dispatchers.IO) { initialize(); processOwned(request) }
    }

    private suspend fun resolveExecutable(): String? = try { executable() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private suspend fun processOwned(request: MediaProcessingRequest, preview: Boolean = false): EngineResult<ProcessedMedia> {
        val binary = resolveExecutable() ?: return EngineResult.Failure(DownloadFailure.DependencyMissing("ffmpeg"))
        val source = File(request.sourcePath)
        if (!source.isFile) return EngineResult.Failure(DownloadFailure.Unavailable(null))
        val clip = request.sourceEntryId != null && !preview
        if (request.sourceEntryId != null && !request.validClip()) return EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
        val caps = if (request.operation == MediaOperation.RENDER_CLIP || request.coverAtMillis != null) capabilities() else null
        if (request.operation == MediaOperation.RENDER_CLIP && (caps?.preciseClips != true || request.framing != ClipFraming.ORIGINAL && !caps.portrait || request.captions.isNotEmpty() && !caps.burnCaptions) || request.coverAtMillis != null && caps?.covers != true) {
            return EngineResult.Failure(DownloadFailure.DependencyMissing("ffmpeg clip capabilities"))
        }
        val id = request.jobId ?: UUID.randomUUID().toString()
        val extension = when (request.operation) { MediaOperation.TRIM -> source.extension; MediaOperation.RENDER_CLIP -> "mp4"; MediaOperation.CONVERT_AUDIO -> request.audioPreset.extension }
        val suffix = if (request.operation == MediaOperation.CONVERT_AUDIO) request.audioPreset.extension else "clip"
        val outputRoot = if (preview) requireNotNull(previewRoot).apply { mkdirs() }
            else if (clip && request.outputDirectory != null) File(request.outputDirectory!!).apply { mkdirs() } else source.parentFile
        if (!outputRoot.isDirectory || !outputRoot.canWrite()) return EngineResult.Failure(DownloadFailure.PermissionDenied)
        val output = File(outputRoot, "${source.nameWithoutExtension.take(60)}-$suffix-$id.$extension")
        val cover = if (request.coverAtMillis != null) File(outputRoot, "${source.nameWithoutExtension.take(60)}-cover-$id.png") else null
        var staging: File? = null
        var record: ClipJobRecord? = null
        try {
            val sourceHash = if (request.sourceEntryId != null) sha256(source) else ""
            request.sourceRevision?.let { expected ->
                if (!expected.matchesHints(source.length(), source.lastModified()) || expected.contentSha256?.equals(sourceHash, ignoreCase = true) == false) return EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
            }
            val old = if (clip) clipStore?.get(id) else null
            if (old != null) {
                if (old.request != request || old.sourceSha256.isNotEmpty() && old.sourceSha256 != sourceHash) return EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
                old.output?.let { receipt ->
                    if (validReceipt(receipt)) {
                        onClipReady(receipt)
                        clipStore?.complete(old.copy(status = ClipJobStatus.SUCCEEDED))
                        return EngineResult.Success(receipt)
                    }
                    if (File(receipt.path).exists()) return EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
                }
            }
            val recoverableCover = cover?.let { it.isFile && old?.output?.coverPath == it.absolutePath && old.output?.coverSha256 == sha256(it) } ?: false
            if (output.exists() || cover?.exists() == true && !recoverableCover) return EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
            record = old?.copy(status = ClipJobStatus.RUNNING) ?: ClipJobRecord(id, request, sourceHash, ClipJobStatus.RUNNING)
            if (clip) clipStore?.save(record)
            staging = Files.createTempDirectory(outputRoot.toPath(), ".openyt-process-").toFile()
            val temporary = staging.resolve("output.$extension")
            if (request.captions.isNotEmpty()) staging.resolve("captions.srt").writeText(captionsForSegment(request), Charsets.UTF_8)
            val result = runTool(binary, FfmpegPlan.arguments(request, temporary.absolutePath), staging, 900)
            if (result.first != 0 || !temporary.isFile || temporary.length() == 0L) throw ProcessingException()
            if (runTool(binary, listOf("-v", "error", "-xerror", "-i", temporary.absolutePath, "-f", "null", "-"), staging, 900).first != 0) throw ProcessingException()
            val tempCover = if (cover != null) staging.resolve("cover.png").also { target ->
                if (runTool(binary, listOf("-v", "error", "-n", "-ss", (request.coverAtMillis!! / 1000.0).toString(), "-i", source.absolutePath, "-frames:v", "1", target.absolutePath), staging, 60).first != 0 || !target.isFile || target.length() == 0L) throw ProcessingException()
            } else null
            if (request.sourceEntryId != null && sha256(source) != sourceHash) throw ProcessingException()
            val revision = MediaRevision(temporary.length(), temporary.lastModified(), sha256(temporary))
            val receipt = ProcessedMedia("processed-$id", "${request.title} ($suffix)", output.absolutePath,
                if (request.operation == MediaOperation.CONVERT_AUDIO) MediaKind.AUDIO else request.kind, temporary.length(),
                revision, cover?.absolutePath, outputRoot.absolutePath, tempCover?.let { sha256(it) })
            record = record.copy(output = receipt)
            if (clip) clipStore?.save(record)
            if (tempCover != null && cover != null) {
                if (!cover.exists()) Files.move(tempCover.toPath(), cover.toPath())
                else if (sha256(cover) != receipt.coverSha256) throw ProcessingException()
            }
            Files.move(temporary.toPath(), output.toPath())
            if (preview) ownedPreviews += output.absolutePath
            record = record.copy(status = ClipJobStatus.SUCCEEDED)
            if (clip) { onClipReady(receipt); clipStore?.complete(record) }
            return EngineResult.Success(receipt)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { record?.let { if (clip) clipStore?.save(it.copy(status = ClipJobStatus.CANCELED)) } }
            throw e
        } catch (_: SecurityException) {
            record?.let { if (clip) clipStore?.save(it.copy(status = ClipJobStatus.FAILED)) }
            return EngineResult.Failure(DownloadFailure.PermissionDenied)
        } catch (_: Exception) {
            record?.let { if (clip) clipStore?.save(it.copy(status = ClipJobStatus.FAILED)) }
            return EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
        } finally { staging?.deleteRecursively() }
    }

    private suspend fun validReceipt(receipt: ProcessedMedia): Boolean = withContext(Dispatchers.IO) {
        val output = File(receipt.path)
        output.isFile && output.length() == receipt.sizeBytes && receipt.mediaRevision?.contentSha256 == sha256(output) &&
            (receipt.coverPath == null || File(receipt.coverPath).let { it.isFile && receipt.coverSha256 == sha256(it) })
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    private suspend fun runTool(binary: String, args: List<String>, directory: File?, seconds: Long): Pair<Int, String> {
        var process: Process? = null
        try {
            process = ProcessBuilder(listOf(binary) + args).directory(directory).redirectErrorStream(true).apply { environment().putAll(processEnvironment()) }.start()
            val running = process
            val text = StringBuilder()
            val reader = thread(isDaemon = true) { runCatching { running.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (text.length < 262_144) text.append(buffer, 0, minOf(n, 262_144 - text.length)) }
            } } }
            val ended = runInterruptible { running.waitFor(seconds, TimeUnit.SECONDS) }
            if (!ended) return -1 to ""
            reader.join(2_000)
            return running.exitValue() to text.toString()
        } finally {
            process?.let { if (it.isAlive) { it.destroyForcibly(); it.waitFor(3, TimeUnit.SECONDS) } }
        }
    }

    private fun captionsForSegment(request: MediaProcessingRequest): String {
        val segment = requireNotNull(request.segment)
        fun time(ms: Long): String = "%02d:%02d:%02d,%03d".format(Locale.ROOT, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        return request.captions.filter { it.endMillis > segment.startMillis && it.startMillis < segment.endMillis }.mapIndexed { index, cue ->
            val start = (cue.startMillis - segment.startMillis).coerceAtLeast(0)
            val end = (cue.endMillis - segment.startMillis).coerceAtMost(segment.endMillis - segment.startMillis)
            "${index + 1}\n${time(start)} --> ${time(end)}\n${cue.text.replace(Regex("<[^>]*>"), "").replace("\u0000", "")}\n"
        }.joinToString("\n")
    }

    private class ProcessingException : Exception()
}
