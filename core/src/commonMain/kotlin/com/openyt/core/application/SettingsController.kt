package com.openyt.core.application

import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns app settings, validates values, and persists the latest change.
 * Persistence is coalesced: rapid changes write only the final state.
 */
class SettingsController(
    private val repository: SettingsRepository?,
    scope: CoroutineScope,
) {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val writes = Channel<AppSettings>(Channel.CONFLATED)

    init {
        scope.launch {
            for (value in writes) {
                runCatching { repository?.save(value) }
            }
        }
    }

    suspend fun restore() {
        runCatching { repository?.load()?.let { _settings.value = it } }
    }

    fun update(value: AppSettings) {
        val validated = value.copy(
            maxConcurrentDownloads = value.maxConcurrentDownloads.coerceIn(
                AppSettings.MIN_CONCURRENT,
                AppSettings.MAX_CONCURRENT,
            ),
        )
        _settings.value = validated
        writes.trySend(validated)
    }

    fun setTheme(mode: ThemeMode) = update(_settings.value.copy(themeMode = mode))

    fun setLanguage(language: AppLanguage) = update(_settings.value.copy(language = language))

    fun setMaxConcurrentDownloads(value: Int) =
        update(_settings.value.copy(maxConcurrentDownloads = value))
}
