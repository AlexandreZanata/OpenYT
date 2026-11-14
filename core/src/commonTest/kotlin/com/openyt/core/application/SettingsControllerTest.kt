package com.openyt.core.application

import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControllerTest {
    @Test
    fun defaultsWhenNoPersistedSettings() = runTest {
        val controller = SettingsController(null, coordinatorScope())

        controller.restore()

        assertEquals(AppSettings(), controller.settings.value)
    }

    @Test
    fun restoresPersistedSettings() = runTest {
        val repository = FakeSettingsRepository(
            stored = AppSettings(ThemeMode.DARK, AppLanguage.EN, 3),
        )
        val controller = SettingsController(repository, coordinatorScope())

        controller.restore()

        assertEquals(ThemeMode.DARK, controller.settings.value.themeMode)
        assertEquals(AppLanguage.EN, controller.settings.value.language)
        assertEquals(3, controller.settings.value.maxConcurrentDownloads)
    }

    @Test
    fun clampsConcurrencyAndPersistsLatestChange() = runTest {
        val repository = FakeSettingsRepository()
        val controller = SettingsController(repository, coordinatorScope())

        controller.setMaxConcurrentDownloads(99)
        advanceUntilIdle()

        assertEquals(AppSettings.MAX_CONCURRENT, controller.settings.value.maxConcurrentDownloads)
        assertEquals(AppSettings.MAX_CONCURRENT, repository.stored.maxConcurrentDownloads)
    }

    @Test
    fun persistsDownloadDirectory() = runTest {
        val repository = FakeSettingsRepository()
        val controller = SettingsController(repository, coordinatorScope())

        controller.setDownloadsDirectory("/home/user/Videos")
        advanceUntilIdle()

        assertEquals("/home/user/Videos", repository.stored.downloadsDirectory)
        assertEquals("/home/user/Videos", controller.settings.value.downloadsDirectory)

        controller.setDownloadsDirectory("   ")
        advanceUntilIdle()

        assertEquals(null, repository.stored.downloadsDirectory)
    }

    @Test
    fun persistsThemeAndLanguage() = runTest {
        val repository = FakeSettingsRepository()
        val controller = SettingsController(repository, coordinatorScope())

        controller.setTheme(ThemeMode.LIGHT)
        controller.setLanguage(AppLanguage.EN)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, repository.stored.themeMode)
        assertEquals(AppLanguage.EN, repository.stored.language)
    }

    @Test
    fun persistsAndClampsOfflineAssetBudgetAndStorageReserve() = runTest {
        val repository = FakeSettingsRepository()
        val controller = SettingsController(repository, coordinatorScope())

        controller.setAssetBudgetBytes(1024L * 1024L * 1024L)
        controller.setStorageReserveBytes(256L * 1024L * 1024L)
        advanceUntilIdle()

        assertEquals(1024L * 1024L * 1024L, repository.stored.assetBudgetBytes)
        assertEquals(256L * 1024L * 1024L, repository.stored.storageReserveBytes)

        controller.setAssetBudgetBytes(128L)
        controller.setStorageReserveBytes(128L)
        advanceUntilIdle()

        assertEquals(16L * 1024L * 1024L, repository.stored.assetBudgetBytes)
        assertEquals(16L * 1024L * 1024L, repository.stored.storageReserveBytes)
    }

    private fun TestScope.coordinatorScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private class FakeSettingsRepository(
        var stored: AppSettings = AppSettings(),
    ) : SettingsRepository {
        override suspend fun load(): AppSettings = stored

        override suspend fun save(settings: AppSettings) {
            stored = settings
        }
    }
}
