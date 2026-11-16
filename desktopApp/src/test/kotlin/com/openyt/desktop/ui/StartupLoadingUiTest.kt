package com.openyt.desktop.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.openyt.core.application.DiagnosticsProvider
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.LibraryController
import com.openyt.core.application.MediaProbe
import com.openyt.core.application.PlatformDiagnostics
import com.openyt.core.application.SettingsController
import com.openyt.core.application.UpdateChecker
import com.openyt.core.application.UpdateController
import com.openyt.core.application.UpdateInstaller
import com.openyt.core.application.UrlOpener
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.ThemeMode
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.ui.OpenYtApp
import com.openyt.ui.components.StartupLoadingScreen
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.theme.OpenYtTheme
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class StartupLoadingUiTest {
    @Test
    fun startupLoadingIndicatorRemainsBoundedInsideWindowSurface() = runSkikoComposeUiTest(
        size = Size(1100f, 780f),
        density = Density(1f),
    ) {
        val tempDir = Files.createTempDirectory("openyt-startup-ui-dark-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val database = createOpenYtDatabase(tempDir.resolve("openyt.db").absolutePath)
            val repository = SqlDelightDownloadRepository(database)
            val settingsController = SettingsController(SqlDelightSettingsRepository(database), scope).apply {
                update(AppSettings(themeMode = ThemeMode.DARK, language = AppLanguage.PT_BR))
            }
            val coordinator = DownloadCoordinator(SuspendedDownloadEngine(), scope, repository = repository)
            val libraryController = LibraryController(repository, scope)
            val updateController = UpdateController(
                checker = UpdateChecker { null },
                currentVersion = "4.2.1",
                scope = scope,
            )

            setContent {
                OpenYtApp(
                    engine = SuspendedDownloadEngine(),
                    coordinator = coordinator,
                    settingsController = settingsController,
                    libraryController = libraryController,
                    mediaProbe = MediaProbe { null },
                    mediaActions = NoopMediaActions,
                    diagnosticsProvider = StubDiagnosticsProvider,
                    updateController = updateController,
                    updateInstaller = UpdateInstaller { false },
                    urlOpener = UrlOpener { false },
                    downloadsDirectory = tempDir.absolutePath,
                    fileAvailability = FileAvailability { false },
                    systemLanguageTag = "pt-BR",
                )
            }

            mainClock.advanceTimeBy(300)
            waitForIdle()

            onNodeWithTag("startup-loading-screen").assertIsDisplayed()
            onNodeWithTag("startup-loading-logo").assertIsDisplayed()
            onNodeWithText(PtBrStrings.features[FeatureText.STARTUP_PREPARING]).assertIsDisplayed()

            val indicator = onNodeWithTag("startup-loading-indicator")
            indicator.assertIsDisplayed()
            indicator.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate,
                ),
            )
            val bounds = indicator.fetchSemanticsNode().boundsInRoot
            assertTrue(
                bounds.height in 2f..12f,
                "Expected compact 4dp loading bar height, got ${bounds.height}",
            )
            assertTrue(
                bounds.width in 120f..220f,
                "Expected compact centered loading bar width, got ${bounds.width}",
            )

            val image = captureToImage().toAwtImage()
            assertOpaque(image)
            val screenshot = File("build/ui-regressions/startup-loading-dark.png").apply {
                parentFile.mkdirs()
            }
            ImageIO.write(image, "png", screenshot)
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun startupLoadingLightThemeAndTransitionToShell() = runSkikoComposeUiTest(
        size = Size(1100f, 780f),
        density = Density(1f),
    ) {
        val tempDir = Files.createTempDirectory("openyt-startup-ui-light-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val database = createOpenYtDatabase(tempDir.resolve("openyt.db").absolutePath)
            val repository = SqlDelightDownloadRepository(database)
            val settingsController = SettingsController(SqlDelightSettingsRepository(database), scope).apply {
                update(AppSettings(themeMode = ThemeMode.LIGHT, language = AppLanguage.PT_BR))
            }
            val coordinator = DownloadCoordinator(SuspendedDownloadEngine(), scope, repository = repository)
            val libraryController = LibraryController(repository, scope)
            val updateController = UpdateController(
                checker = UpdateChecker { null },
                currentVersion = "4.2.1",
                scope = scope,
            )
            var startupReady by mutableStateOf(false)

            setContent {
                OpenYtApp(
                    engine = SuspendedDownloadEngine(),
                    coordinator = coordinator,
                    settingsController = settingsController,
                    libraryController = libraryController,
                    mediaProbe = MediaProbe { null },
                    mediaActions = NoopMediaActions,
                    diagnosticsProvider = StubDiagnosticsProvider,
                    updateController = updateController,
                    updateInstaller = UpdateInstaller { false },
                    urlOpener = UrlOpener { false },
                    downloadsDirectory = tempDir.absolutePath,
                    fileAvailability = FileAvailability { false },
                    systemLanguageTag = "pt-BR",
                    isStartupReady = startupReady,
                )
            }

            mainClock.advanceTimeBy(300)
            waitForIdle()

            onNodeWithTag("startup-loading-screen").assertIsDisplayed()
            val lightShot = File("build/ui-regressions/startup-loading-light.png").apply {
                parentFile.mkdirs()
            }
            val image = captureToImage().toAwtImage()
            assertOpaque(image)
            ImageIO.write(image, "png", lightShot)

            runBlocking {
                settingsController.restore()
                coordinator.restore()
                libraryController.restore()
            }
            startupReady = true
            mainClock.advanceTimeBy(400)
            waitForIdle()

            onNodeWithText(PtBrStrings.tabHome).assertIsDisplayed()
            onNodeWithText(PtBrStrings.tabDownloads).assertIsDisplayed()
            onNodeWithText(PtBrStrings.tabLibrary).assertIsDisplayed()
            onNodeWithText(PtBrStrings.tabSettings).assertIsDisplayed()
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun startupLoadingAdaptsToNarrowScreenAndEnglishLocale() = runSkikoComposeUiTest(
        size = Size(320f, 640f),
        density = Density(1f, fontScale = 1.5f),
    ) {
        setContent {
            OpenYtTheme(darkTheme = true) {
                CompositionLocalProvider(LocalStrings provides EnStrings) {
                    Surface {
                        StartupLoadingScreen()
                    }
                }
            }
        }
        mainClock.advanceTimeBy(300)
        waitForIdle()

        onNodeWithTag("startup-loading-logo").assertIsDisplayed()
        onNodeWithTag("startup-loading-indicator").assertIsDisplayed()
        onNodeWithText(EnStrings.features[FeatureText.STARTUP_PREPARING]).assertIsDisplayed()

        val bounds = onNodeWithTag("startup-loading-indicator").fetchSemanticsNode().boundsInRoot
        assertEquals(5f, bounds.height, 0.5f)
        assertTrue(bounds.width in 120f..220f, "Unexpected width ${bounds.width}")
    }

    private fun assertOpaque(image: java.awt.image.BufferedImage) {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        assertTrue(pixels.all { it ushr 24 == 255 }, "Expected fully opaque UI capture")
    }

    private object StubDiagnosticsProvider : DiagnosticsProvider {
        override suspend fun snapshot(): PlatformDiagnostics = PlatformDiagnostics(
            appVersion = "4.2.1",
            platform = "Linux",
            engineName = "test",
            engineVersion = "0",
            downloadDirectory = "/tmp",
            databasePath = null,
        )

        override fun copyToClipboard(text: String): Boolean = false
    }
}
