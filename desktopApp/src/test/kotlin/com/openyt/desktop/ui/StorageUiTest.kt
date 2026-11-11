package com.openyt.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.openyt.core.application.ScannedMedia
import com.openyt.core.application.StorageReport
import com.openyt.core.domain.MediaKind
import com.openyt.ui.components.StorageDialog
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.theme.OpenYtTheme
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class StorageUiTest(private val scenario: Scenario) {
    @Test
    fun longWindowsPathsScrollAndRecoveryActionsStayVisible() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        var imported = false
        val files = (1..12).map { index ->
            ScannedMedia(
                path = "C:\\Users\\João\\Downloads\\Curso Básico OBS Studio\\" +
                    "$index - Configuração e transmissão com um nome muito longo.mkv",
                title = "Aula $index",
                kind = MediaKind.VIDEO,
                sizeBytes = 100_000,
            )
        }
        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    StorageDialog(
                        report = StorageReport(0, 0, emptyList(), emptyList(), emptyList(), files),
                        loading = false,
                        failed = false,
                        onRetry = {},
                        onImport = { imported = true },
                        onDismiss = {},
                    )
                }
            }
        }
        onNodeWithText(scenario.strings.closeAction).assertIsDisplayed()
        onNodeWithText(scenario.strings.watchFolderImport).assertIsDisplayed()
        onNodeWithTag("storage-content").performScrollToNode(hasText(files.last().path))
        onNodeWithText(files.last().path).assertIsDisplayed()

        val screenshot = File("build/ui-regressions/storage-${scenario.name}.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", screenshot)
        onNodeWithText(scenario.strings.watchFolderImport).performClick()
        assertTrue(imported)
    }

    @Test
    fun scanFailureOffersRetryAndClose() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        var retried = false
        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    StorageDialog(
                        report = null,
                        loading = false,
                        failed = true,
                        onRetry = { retried = true },
                        onImport = null,
                        onDismiss = {},
                    )
                }
            }
        }
        onNodeWithText(scenario.strings.storageFailed).assertIsDisplayed()
        onNodeWithText(scenario.strings.closeAction).assertIsDisplayed()
        onNodeWithText(scenario.strings.retryAction).assertIsDisplayed().performClick()
        assertTrue(retried)
    }

    data class Scenario(
        val name: String,
        val size: Size,
        val fontScale: Float,
        val dark: Boolean,
        val strings: AppStrings,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios() = listOf(
            arrayOf(Scenario("mobile-light-pt", Size(360f, 640f), 1f, false, PtBrStrings)),
            arrayOf(Scenario("mobile-large-dark-pt", Size(320f, 640f), 1.5f, true, PtBrStrings)),
            arrayOf(Scenario("desktop-dark-pt", Size(1280f, 800f), 1f, true, PtBrStrings)),
            arrayOf(Scenario("desktop-light-en", Size(1024f, 600f), 1.3f, false, EnStrings)),
        )
    }
}
