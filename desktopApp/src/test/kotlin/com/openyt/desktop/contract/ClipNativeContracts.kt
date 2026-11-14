package com.openyt.desktop.contract

import com.openyt.core.application.*
import com.openyt.core.domain.*
import com.openyt.data.*
import com.openyt.desktop.downloader.ExecutableLocator
import com.openyt.media.ffmpeg.FfmpegMediaProcessor
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.openyt.desktop.player.DesktopPlaybackLauncher

internal fun verifyInstalledClipWorkflows(source: File) = runBlocking {
    val tools = ExecutableLocator()
    val binary = checkNotNull(tools.findFfmpeg())
    val probe = checkNotNull(tools.findFfprobe())
    val root = source.parentFile.resolve("clip-proof-${UUID.randomUUID()}").apply { mkdirs() }
    val database = createOpenYtDatabase(root.resolve("proof.db").path)
    val store = SqlDelightClipJobStore(database)
    val assets = JvmOfflineAssetStore(root.resolve("assets"), SqlDelightOfflineAssetRepository(database), SqlDelightAssetOperationRepository(database))
    val processor = FfmpegMediaProcessor({ binary }, clipStore = store, previewRoot = root.resolve("previews"),
        onClipReady = ClipCoverPublisher(assets, JvmMediaFileInspector())::publish)
    fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    fun command(vararg args: String): String {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "Native clip probe failed" }
        return text.trim()
    }
    val original = hash(source)
    val capabilities = processor.capabilities()
    check(capabilities.preciseClips && capabilities.portrait && capabilities.burnCaptions && capabilities.covers)
    val base = MediaProcessingRequest(source.path, "Native clip", MediaKind.VIDEO, MediaOperation.RENDER_CLIP,
        MediaSegment(1_000, 3_000), framing = ClipFraming.FIT_PORTRAIT, sourceEntryId = "native-source", sourceDurationMillis = 30_000,
        jobId = "native-${UUID.randomUUID()}")
    val requests = listOf(base, base.copy(jobId = "native-${UUID.randomUUID()}", operation = MediaOperation.TRIM, framing = ClipFraming.ORIGINAL),
        base.copy(jobId = "native-${UUID.randomUUID()}", captions = listOf(ClipCaption(1_000, 3_000, "AÇÃO d'água — OpenYT")), coverAtMillis = 1_500))
    check(processor.queueClips(requests))
    val outputs = requests.map { request -> (processor.process(request) as? EngineResult.Success)?.value ?: error("Installed clip processing failed") }
    val dimensions = command(probe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", outputs.first().path)
    check(dimensions == "720,1280")
    check(File(checkNotNull(outputs.last().coverPath)).isFile)
    check(assets.getAssetsForEntry(outputs.last().id).single().isVerified)
    check(SqlDelightDownloadRepository(database).loadLibrary().map { it.id }.toSet() == outputs.map { it.id }.toSet())
    check((processor.process(requests.first()) as EngineResult.Success).value == outputs.first())
    val player = DesktopPlaybackLauncher()
    try {
        check(player.isVlcAvailable())
        for (output in outputs) {
            player.play(listOf(PlayableMedia(output.path, "Exported clip", MediaKind.VIDEO)), 0)
            withTimeout(15_000) {
                while (true) {
                    val snapshot = player.captureSnapshot()
                    if (snapshot?.state == PlaybackSessionState.PLAYING && snapshot.positionMillis >= 100) break
                    delay(50)
                }
            }
            player.stop()
        }
    } finally { player.close() }
    val reopened = SqlDelightClipJobStore(createOpenYtDatabase(root.resolve("proof.db").path))
    check(reopened.load("native-source").count { it.status == ClipJobStatus.SUCCEEDED } == 3)
    val preview = (processor.previewClip(requests.last()) as EngineResult.Success).value
    processor.releasePreview(preview)
    check(!File(preview.path).exists())
    check(original == hash(source))
    println("PASS installed FFmpeg/VLC: three clips decoded and played, portrait/captions/cover, durable assets, reopened atomic receipts, idempotent retry and source preservation")
}
