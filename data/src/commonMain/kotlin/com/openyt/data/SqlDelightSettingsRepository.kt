package com.openyt.data

import com.openyt.core.application.SettingsRepository
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.OrganizationPreset
import com.openyt.core.domain.ThemeMode
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightSettingsRepository(
    database: OpenYtDatabase,
) : SettingsRepository {
    private val queries = database.downloadQueries

    override suspend fun load(): AppSettings = withContext(Dispatchers.Default) {
        val row = queries.selectSettings().executeAsOneOrNull()
        AppSettings(
            clipboardSuggestions = row?.clipboard_suggestions == 1L,
            downloadNotifications = row?.download_notifications == 1L,
            themeMode = parseTheme(row?.theme_mode),
            language = parseLanguage(row?.language),
            maxConcurrentDownloads = (row?.max_concurrent
                ?: AppSettings.DEFAULT_CONCURRENT.toLong()).toInt().coerceIn(
                AppSettings.MIN_CONCURRENT,
                AppSettings.MAX_CONCURRENT,
            ),
            downloadsDirectory = row?.downloads_directory,
            storageTreeUri = row?.storage_tree_uri,
            speedLimitBytesPerSecond = row?.speed_limit_bytes_per_second,
            downloadWindowStartMinutes = row?.download_window_start_minutes?.toInt(),
            downloadWindowEndMinutes = row?.download_window_end_minutes?.toInt(),
            organizationPreset = parsePreset(row?.organization_preset),
            fileOrganizationTemplate = row?.file_organization_template,
            defaultProfileId = row?.default_profile_id,
        )
    }

    override suspend fun save(settings: AppSettings) = withContext(Dispatchers.Default) {
        queries.upsertSettings(
            clipboard_suggestions = if (settings.clipboardSuggestions) 1L else 0L,
            download_notifications = if (settings.downloadNotifications) 1L else 0L,
            theme_mode = settings.themeMode.name,
            language = settings.language.name,
            max_concurrent = settings.maxConcurrentDownloads.toLong(),
            downloads_directory = settings.downloadsDirectory,
            storage_tree_uri = settings.storageTreeUri,
            speed_limit_bytes_per_second = settings.speedLimitBytesPerSecond,
            download_window_start_minutes = settings.downloadWindowStartMinutes?.toLong(),
            download_window_end_minutes = settings.downloadWindowEndMinutes?.toLong(),
            organization_preset = settings.organizationPreset.name,
            file_organization_template = settings.fileOrganizationTemplate,
            default_profile_id = settings.defaultProfileId,
        )
        Unit
    }

    private fun parseTheme(value: String?): ThemeMode =
        runCatching { ThemeMode.valueOf(value.orEmpty()) }.getOrDefault(ThemeMode.SYSTEM)

    private fun parseLanguage(value: String?): AppLanguage =
        runCatching { AppLanguage.valueOf(value.orEmpty()) }.getOrDefault(AppLanguage.SYSTEM)

    private fun parsePreset(value: String?): OrganizationPreset =
        runCatching { OrganizationPreset.valueOf(value.orEmpty()) }
            .getOrDefault(OrganizationPreset.PLAYLIST)
}
