package com.openyt.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.MediaActions
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.ShareableMedia
import com.openyt.ui.components.DownloadProgressBar
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.DownloadsScreen
import com.openyt.ui.theme.OpenYtTheme
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DownloadsUiTest {
    @Test
    fun narrowScreenWithLargeTextKeepsDownloadActionsReachable() = runSkikoComposeUiTest(
        size = Size(320f, 640f),
        density = Density(1f, fontScale = 1.5f),
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DownloadCoordinator(SuspendedDownloadEngine(), scope)
        coordinator.enqueue(
            media = MediaInfo(
                sourceId = "narrow",
                source = "youtube",
                title = "Configuração e transmissão de uma aula com nome longo",
                webpageUrl = "https://example.com/watch",
            ),
            request = DownloadRequest("https://example.com/watch", destinationDirectory = "/tmp/ui-test"),
        )
        try {
            setContent {
                OpenYtTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                        Surface { DownloadsScreen(coordinator, NoopMediaActions) }
                    }
                }
            }
            onNodeWithText(PtBrStrings.pauseAction).performScrollTo().assertIsDisplayed()
            onNodeWithText(PtBrStrings.cancelAction).performScrollTo().assertIsDisplayed()
            val screenshot = File("build/ui-regressions/downloads-mobile-large.png")
            screenshot.parentFile.mkdirs()
            ImageIO.write(captureToImage().toAwtImage(), "png", screenshot)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun progressBarMatchesGolden() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                OpenYtTheme(darkTheme = true) {
                    Box(Modifier.padding(8.dp)) {
                        DownloadProgressBar(
                            title = "test",
                            percent = 42.5,
                            modifier = Modifier.width(320.dp),
                        )
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        val image = onNodeWithContentDescription("test: 42%").captureToImage().toAwtImage()
        assertGolden("download-progress", image)
    }

    @Test
    fun downloadsScreenShowsAnimatedProgress() = runComposeUiTest {
        val engine = SuspendedDownloadEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DownloadCoordinator(engine, scope)
        coordinator.enqueue(
            media = MediaInfo(
                sourceId = "ui-test",
                source = "youtube",
                title = "Vídeo de teste",
                author = "Canal",
                webpageUrl = "https://example.com/watch",
            ),
            request = DownloadRequest(
                url = "https://example.com/watch",
                destinationDirectory = "/tmp/openyt-ui-test",
            ),
        )

        try {
            setContent {
                OpenYtTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                        DownloadsScreen(
                            coordinator = coordinator,
                            mediaActions = NoopMediaActions,
                        )
                    }
                }
            }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()

            onNodeWithText("Vídeo de teste").assertExists()
            onNodeWithText("42%").assertExists()
            onNodeWithContentDescription("Vídeo de teste: 42%").assertExists()
        } finally {
            scope.cancel()
        }
    }

    private fun assertGolden(name: String, actual: java.awt.image.BufferedImage) {
        val buildFile = File("build/ui-goldens/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(actual, "png", buildFile)

        val golden = File("src/test/resources/goldens/$name.png")
        if (!golden.isFile) {
            if (System.getProperty("openyt.updateGoldens") == "1") {
                golden.parentFile.mkdirs()
                ImageIO.write(actual, "png", golden)
                return
            }
            error("missing golden $golden; run with -Dopenyt.updateGoldens=1")
        }

        val expected = ImageIO.read(golden)
        assertEquals(expected.width, actual.width, "golden width changed")
        assertEquals(expected.height, actual.height, "golden height changed")
        var different = 0
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val a = expected.getRGB(x, y)
                val b = actual.getRGB(x, y)
                val diff = maxOf(
                    kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
                    kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
                    kotlin.math.abs((a and 0xFF) - (b and 0xFF)),
                )
                if (diff > 2) different++
            }
        }
        val ratio = different.toDouble() / (expected.width * expected.height)
        assertTrue(ratio < 0.01, "golden mismatch: ${"%.2f".format(ratio * 100)}% pixels")
    }
}

internal class SuspendedDownloadEngine : DownloadEngine {
    override suspend fun inspect(url: String): EngineResult<Inspection> =
        EngineResult.Failure(DownloadFailure.UnsupportedSource)

    override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Starting)
        emit(
            DownloadEvent.Progress(
                percent = 42.5,
                speedBytesPerSecond = 5_242_880,
                etaSeconds = 37,
            ),
        )
        awaitCancellation()
    }

    override suspend fun cancel(downloadId: String) = Unit

    override suspend fun capabilities(): EngineCapabilities = EngineCapabilities(
        engineName = "ui-test",
        engineVersion = "0",
        supportsPause = false,
        supportsSubtitles = false,
    )

    override fun close() = Unit
}

internal object NoopMediaActions : MediaActions {
    override val canShare: Boolean = false
    override val canOpen: Boolean = false
    override val canReveal: Boolean = false

    override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit

    override fun open(media: ShareableMedia): Boolean = false

    override fun reveal(media: ShareableMedia): Boolean = false
}
