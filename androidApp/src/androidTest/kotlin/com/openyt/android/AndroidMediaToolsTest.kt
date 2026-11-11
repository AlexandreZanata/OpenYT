package com.openyt.android

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.downloader.AndroidMediaTagger
import com.openyt.android.player.androidMediaProcessor
import com.openyt.android.probe.AndroidMediaProbe
import com.openyt.core.application.AudioMetadata
import com.openyt.core.application.MediaOperation
import com.openyt.core.application.MediaProcessingRequest
import com.openyt.core.domain.*
import com.openyt.data.RssFeedReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaToolsTest {
    @Test fun bundledFfmpegTrimsConvertsNormalizesAndTagsWithoutChangingTheSource() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "media-tools-contract").apply { mkdirs() }
        val source = File(directory, "Ação original.mp4")
        instrumentation.context.assets.open("media-tools-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val bytes = source.readBytes()
        try {
            val process = ProcessBuilder(com.openyt.android.downloader.androidFfmpegExecutable(context)!!, "-version")
                .redirectErrorStream(true).apply { environment().putAll(com.openyt.android.downloader.androidFfmpegEnvironment(context)) }.start()
            val version = process.inputStream.bufferedReader().readText()
            assertEquals("Bundled FFmpeg startup: $version", 0, process.waitFor())
            val processor = androidMediaProcessor(context)
            val trimmed = processor.process(MediaProcessingRequest(source.absolutePath, "Ação", MediaKind.VIDEO, MediaOperation.TRIM, MediaSegment(1000, 3000)))
            assertTrue("trim=$trimmed", trimmed is EngineResult.Success)
            val clip = (trimmed as EngineResult.Success).value
            val duration = AndroidMediaProbe().probe(clip.path)?.durationMillis ?: 0
            assertTrue("clip duration=$duration", duration in 1500..3500)
            val converted = processor.process(MediaProcessingRequest(source.absolutePath, "Ação", MediaKind.VIDEO, MediaOperation.CONVERT_AUDIO, audioPreset = AudioPreset.M4A, normalize = true))
            assertTrue("conversion=$converted", converted is EngineResult.Success)
            val audio = (converted as EngineResult.Success).value
            assertNotNull(AndroidMediaProbe().probe(audio.path)?.audioCodec)
            assertTrue(AndroidMediaTagger(context).writeTags(audio.path, AudioMetadata("Tagged title", "Tagged artist")))
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(audio.path)
                assertEquals("Tagged title", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                assertEquals("Tagged artist", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            } finally { retriever.release() }
            assertArrayEquals(bytes, source.readBytes())
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".openyt-process-") || it.name.contains(".openyt-tag-") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun platformXmlParserReadsFeedsAndRejectsExternalEntities() {
        val episodes = RssFeedReader.parse("""<rss><channel><item><guid>episode-1</guid><title>Ação</title><enclosure url="https://example.com/episode.mp3"/></item></channel></rss>""")
        assertEquals("Ação", episodes.single().title)
        var rejected = false
        try { RssFeedReader.parse("""<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///private">]><rss>&x;</rss>""") }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
