package com.openyt.media.ffmpeg

import com.openyt.core.application.*
import com.openyt.core.domain.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlin.test.*

class ClipRenderingContractTest {
    private class Store : ClipJobStore {
        val records = mutableMapOf<String, ClipJobRecord>()
        var failSuccessOnce = false
        var failRunningOnce = false
        override suspend fun get(id: String) = records[id]
        override suspend fun save(record: ClipJobRecord) {
            if (record.status == ClipJobStatus.SUCCEEDED && failSuccessOnce) { failSuccessOnce = false; error("injected publication commit failure") }
            if (record.status == ClipJobStatus.RUNNING && failRunningOnce) { failRunningOnce = false; error("injected second interrupted attempt") }
            records[record.id] = record
        }
        override suspend fun load(entryId: String) = records.values.filter { it.request.sourceEntryId == entryId }
        override suspend fun interruptRunning() { records.replaceAll { _, record -> if (record.status == ClipJobStatus.RUNNING) record.copy(status = ClipJobStatus.INTERRUPTED) else record } }
    }

    private fun request(path: String, id: String = "one") = MediaProcessingRequest(path, "Fixture", MediaKind.VIDEO,
        MediaOperation.RENDER_CLIP, MediaSegment(1_000, 3_000), framing = ClipFraming.FIT_PORTRAIT,
        sourceEntryId = "entry", sourceDurationMillis = 6_000, jobId = id)

    @Test fun recipesRejectInvalidCapabilitiesAndDoNotInterpolatePathsIntoFilters() {
        val request = request("C:\\Vídeo d'ação;[x].mp4").copy(captions = listOf(ClipCaption(1_000, 3_000, "Ação d'água")))
        assertTrue(request.validClip())
        assertFalse(request.copy(jobId = "../outside").validClip())
        assertFalse(request.copy(sourceDurationMillis = 1_000).validClip())
        assertFalse(request.copy(operation = MediaOperation.TRIM).validClip())
        assertFalse(request.copy(coverAtMillis = 4_000).validClip())
        assertFalse(request.copy(outputDirectory = "content://external/tree").validClip())
        val args = FfmpegPlan.arguments(request, "C:\\output.mp4")
        assertEquals(request.sourcePath, args[args.indexOf("-i") + 1])
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"), "Accurate input seek resets the caption timeline")
        assertTrue(args[args.indexOf("-vf") + 1].contains("subtitles=captions.srt"))
        assertFalse(args[args.indexOf("-vf") + 1].contains(request.sourcePath))
        assertTrue("-n" in args)
    }

    @Test fun rendersPortraitCaptionsCoverAndPreviewWithCrashSafeRetryAndSourcePreservation() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("OPENYT_RUN_MEDIA_TESTS") == "1")
        val binary = System.getenv("OPENYT_FFMPEG") ?: "/usr/bin/ffmpeg"
        val probe = System.getenv("OPENYT_FFPROBE") ?: File(File(binary).parentFile, "ffprobe").absolutePath
        val root = Files.createTempDirectory("openyt-clips-").toFile()
        val originalLocale = Locale.getDefault()
        fun command(vararg args: String): String {
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), output)
            return output.trim()
        }
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        try {
            val sourceDirectory = root.resolve("read-only-source").apply { mkdirs() }
            val destination = root.resolve("exports").apply { mkdirs() }
            val source = sourceDirectory.resolve("Vídeo d'ação #1.mp4")
            command(binary, "-v", "error", "-f", "lavfi", "-i", "color=c=black:size=320x180:rate=10:duration=6", "-f", "lavfi", "-i", "sine=duration=6", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", source.path)
            val original = digest(source)
            sourceDirectory.setWritable(false)
            Locale.setDefault(Locale.forLanguageTag("ar"))
            val store = Store()
            val processor = FfmpegMediaProcessor({ binary }, clipStore = store, previewRoot = root.resolve("private-preview"))
            val caps = processor.capabilities()
            assertTrue(caps.preciseClips && caps.portrait && caps.burnCaptions && caps.covers)
            val first = request(source.path).copy(captions = listOf(ClipCaption(1_000, 3_000, "AÇÃO: conteúdo salvo")), coverAtMillis = 1_500, outputDirectory = destination.path)
            val copy = request(source.path, "two").copy(operation = MediaOperation.TRIM, framing = ClipFraming.ORIGINAL, segment = MediaSegment(3_000, 5_000), outputDirectory = destination.path)
            val crop = request(source.path, "three").copy(framing = ClipFraming.CENTER_CROP, outputDirectory = destination.path)
            assertTrue(processor.queueClips(listOf(first, copy, crop)))
            store.failSuccessOnce = true
            assertIs<EngineResult.Failure>(processor.process(first))
            val output = assertIs<EngineResult.Success<ProcessedMedia>>(processor.process(first)).value
            assertEquals(ClipJobStatus.SUCCEEDED, store.get("one")!!.status)
            assertEquals(output, assertIs<EngineResult.Success<ProcessedMedia>>(processor.process(first)).value)
            File(output.path).delete()
            store.failRunningOnce = true
            assertIs<EngineResult.Failure>(processor.process(first))
            assertEquals(output.coverSha256, store.get("one")!!.output!!.coverSha256, "A second failure must preserve cover ownership proof")
            assertIs<EngineResult.Success<ProcessedMedia>>(processor.process(first))
            val streams = command(probe, "-v", "error", "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", output.path)
            assertTrue(streams.contains("h264,720,1280"), streams)
            assertTrue(streams.contains("aac"), streams)
            val duration = command(probe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", output.path).toDouble()
            assertTrue(duration in 1.8..2.2, "duration=$duration")
            val frame = root.resolve("caption-proof.png")
            command(binary, "-v", "error", "-ss", "0.5", "-i", output.path, "-frames:v", "1", frame.path)
            val image = ImageIO.read(frame)
            val white = (0 until image.width).sumOf { x -> (0 until image.height).count { y -> val rgb = image.getRGB(x, y); (rgb shr 16 and 255) > 160 && (rgb shr 8 and 255) > 160 && (rgb and 255) > 160 } }
            assertTrue(white > 100, "The black fixture must contain visibly rendered caption pixels: $white")
            assertTrue(File(output.coverPath!!).length() > 0)
            assertIs<EngineResult.Success<ProcessedMedia>>(processor.process(copy))
            assertIs<EngineResult.Success<ProcessedMedia>>(processor.process(crop))
            val preview = assertIs<EngineResult.Success<ProcessedMedia>>(processor.previewClip(first)).value
            assertEquals(root.resolve("private-preview").canonicalPath, File(preview.path).parentFile.canonicalPath)
            assertEquals(3, store.records.size, "Preview must not create export jobs")
            processor.releasePreview(preview)
            assertFalse(File(preview.path).exists())
            assertEquals(original, digest(source))
            assertTrue(root.listFiles()!!.none { it.name.startsWith(".openyt-process-") })
        } finally { Locale.setDefault(originalLocale); root.resolve("read-only-source").setWritable(true); root.deleteRecursively() }
    }

    @Test fun cancellingActualEncodingReapsTheProcessAndPreservesTheOriginal() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("OPENYT_RUN_MEDIA_TESTS") == "1")
        val binary = System.getenv("OPENYT_FFMPEG") ?: "/usr/bin/ffmpeg"
        val root = Files.createTempDirectory("openyt-cancel-clip-").toFile()
        try {
            val source = root.resolve("source.mp4")
            val generate = ProcessBuilder(binary, "-v", "error", "-f", "lavfi", "-i", "color=c=black:size=320x180:rate=30:duration=70", "-c:v", "libx264", "-preset", "ultrafast", source.path).redirectErrorStream(true).start()
            val diagnostic = generate.inputStream.bufferedReader().readText()
            assertEquals(0, generate.waitFor(), diagnostic)
            val original = source.readBytes().toList()
            val store = Store()
            val processor = FfmpegMediaProcessor({ binary }, clipStore = store)
            val request = request(source.path, "cancel").copy(segment = MediaSegment(0, 60_000), sourceDurationMillis = 70_000)
            assertTrue(processor.queueClips(listOf(request)))
            val job = launch(Dispatchers.Default) { processor.process(request) }
            withTimeout(5_000) {
                while (root.listFiles().orEmpty().none { it.name.startsWith(".openyt-process-") }) delay(10)
            }
            val clock = System.nanoTime()
            job.cancelAndJoin()
            assertTrue((System.nanoTime() - clock) / 1_000_000 < 5_000, "Cancel must reap the native process promptly")
            assertEquals(ClipJobStatus.CANCELED, store.get("cancel")!!.status)
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".openyt-process-") })
            assertEquals(original, source.readBytes().toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun preciseExportsPreserveAnamorphicDisplayAspectBeforeFitAndCrop() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("OPENYT_RUN_MEDIA_TESTS") == "1")
        val binary = System.getenv("OPENYT_FFMPEG") ?: "/usr/bin/ffmpeg"
        val probe = System.getenv("OPENYT_FFPROBE") ?: File(File(binary).parentFile, "ffprobe").absolutePath
        val root = Files.createTempDirectory("openyt-anamorphic-").toFile()
        fun command(vararg args: String): String {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), text)
            return text.trim()
        }
        try {
            val source = root.resolve("anamorphic.mp4")
            command(binary, "-v", "error", "-f", "lavfi", "-i", "color=c=white:size=720x576:rate=5:duration=4", "-vf", "setsar=16/15", "-c:v", "libx264", source.path)
            val processor = FfmpegMediaProcessor({ binary }, clipStore = Store())
            for (framing in ClipFraming.entries) {
                val request = request(source.path, framing.name.replace('_', '-')).copy(framing = framing, sourceDurationMillis = 4_000)
                assertTrue(processor.queueClips(listOf(request)))
                val output = assertIs<EngineResult.Success<ProcessedMedia>>(processor.process(request)).value
                val metadata = command(probe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,sample_aspect_ratio,display_aspect_ratio", "-of", "csv=p=0", output.path)
                if (framing == ClipFraming.ORIGINAL) assertEquals("768,576,1:1,4:3", metadata)
                else assertEquals("720,1280,1:1,9:16", metadata)
                if (framing == ClipFraming.FIT_PORTRAIT) {
                    val frame = root.resolve("fit.png")
                    command(binary, "-v", "error", "-ss", "0.5", "-i", output.path, "-frames:v", "1", frame.path)
                    val image = ImageIO.read(frame)
                    val contentRows = (0 until image.height).count { (image.getRGB(360, it) and 255) > 200 }
                    assertTrue(contentRows in 538..542, "4:3 content must occupy 720x540 square pixels: $contentRows")
                }
            }
        } finally { root.deleteRecursively() }
    }
}
