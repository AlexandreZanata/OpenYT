package com.openyt.desktop.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.openyt.core.application.DiagnosticsProvider
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.LibraryController
import com.openyt.core.application.PlatformDiagnostics
import com.openyt.core.application.SettingsController
import com.openyt.core.application.SettingsRepository
import com.openyt.core.application.UpdateChecker
import com.openyt.core.application.UpdateController
import com.openyt.core.application.UpdateInstaller
import com.openyt.core.application.UrlOpener
import com.openyt.core.domain.AppSettings
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.SettingsScreen
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SettingsOfflineUiTest {
    private class MemorySettingsRepository(
        var stored: AppSettings = AppSettings(),
    ) : SettingsRepository {
        override suspend fun load(): AppSettings = stored

        override suspend fun save(settings: AppSettings) {
            stored = settings
        }
    }

    @Test
    fun offlineBudgetAndReservePresetChipsUpdateControllerAcrossLocalesAndNarrowViewports() {
        val scenarios = listOf(
            Scenario(PtBrStrings, Size(960f, 900f), Density(1f, 1f)),
            Scenario(EnStrings, Size(320f, 640f), Density(1f, 1.5f)),
        )
        for (scenario in scenarios) {
            verifyBudgetAndReserveChips(scenario)
        }
    }

    private fun verifyBudgetAndReserveChips(scenario: Scenario) = runSkikoComposeUiTest(
        size = scenario.size,
        density = scenario.density,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settingsRepo = MemorySettingsRepository()
        val settingsController = SettingsController(settingsRepo, scope)
        val engine = SuspendedDownloadEngine()
        val coordinator = DownloadCoordinator(engine, scope)
        val libraryController = LibraryController(repository = null, scope = scope)
        val diagnosticsProvider = object : DiagnosticsProvider {
            override suspend fun snapshot() = PlatformDiagnostics(
                appVersion = "0.1.0",
                platform = "Linux",
                engineName = "yt-dlp",
                engineVersion = "2026.01.01",
                downloadDirectory = "/tmp/openyt-downloads",
                databasePath = "/tmp/openyt.db",
            )

            override fun copyToClipboard(text: String): Boolean = true
        }
        val updateController = UpdateController(
            checker = UpdateChecker { null },
            currentVersion = "0.1.0",
            scope = scope,
        )

        try {
            setContent {
                OpenYtTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalStrings provides scenario.strings) {
                        Surface {
                            SettingsScreen(
                                engine = engine,
                                downloadsDirectory = "/tmp/openyt-downloads",
                                settingsController = settingsController,
                                coordinator = coordinator,
                                libraryController = libraryController,
                                diagnosticsProvider = diagnosticsProvider,
                                updateController = updateController,
                                updateInstaller = UpdateInstaller { false },
                                urlOpener = UrlOpener { false },
                            )
                        }
                    }
                }
            }

            onNodeWithText(scenario.strings.offline.assetBudgetLabel)
                .performScrollTo()
                .assertIsDisplayed()
            onNodeWithText("1 GiB").performScrollTo().performClick()
            waitForIdle()
            assertEquals(1024L * 1024L * 1024L, settingsController.settings.value.assetBudgetBytes)
            assertEquals(1024L * 1024L * 1024L, settingsRepo.stored.assetBudgetBytes)
            onNodeWithText("1 GiB").assertIsSelected()

            onNodeWithText(scenario.strings.offline.storageReserveLabel)
                .performScrollTo()
                .assertIsDisplayed()
            val reserve256Chip = onAllNodesWithText("256 MiB")[1]
            reserve256Chip.performScrollTo().performClick()
            waitForIdle()
            assertEquals(256L * 1024L * 1024L, settingsController.settings.value.storageReserveBytes)
            assertEquals(256L * 1024L * 1024L, settingsRepo.stored.storageReserveBytes)
            reserve256Chip.assertIsSelected()
        } finally {
            scope.cancel()
        }
    }

    private data class Scenario(
        val strings: AppStrings,
        val size: Size,
        val density: Density,
    )
}
