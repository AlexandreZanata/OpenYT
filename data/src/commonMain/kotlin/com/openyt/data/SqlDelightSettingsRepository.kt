package com.openyt.data

import com.openyt.core.application.SettingsRepository
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
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
            themeMode = parseTheme(row?.theme_mode),
            language = parseLanguage(row?.language),
            maxConcurrentDownloads = (row?.max_concurrent
                ?: AppSettings.DEFAULT_CONCURRENT.toLong()).toInt().coerceIn(
                AppSettings.MIN_CONCURRENT,
                AppSettings.MAX_CONCURRENT,
            ),
            downloadsDirectory = row?.downloads_directory,
            storageTreeUri = row?.storage_tree_uri,
        )
    }

    override suspend fun save(settings: AppSettings) = withContext(Dispatchers.Default) {
        queries.upsertSettings(
            theme_mode = settings.themeMode.name,
            language = settings.language.name,
            max_concurrent = settings.maxConcurrentDownloads.toLong(),
            downloads_directory = settings.downloadsDirectory,
            storage_tree_uri = settings.storageTreeUri,
        )
        Unit
    }

    private fun parseTheme(value: String?): ThemeMode =
        runCatching { ThemeMode.valueOf(value.orEmpty()) }.getOrDefault(ThemeMode.SYSTEM)

    private fun parseLanguage(value: String?): AppLanguage =
        runCatching { AppLanguage.valueOf(value.orEmpty()) }.getOrDefault(AppLanguage.PT_BR)
}
