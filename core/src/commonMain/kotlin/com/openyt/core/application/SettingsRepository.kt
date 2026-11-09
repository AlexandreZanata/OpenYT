package com.openyt.core.application

import com.openyt.core.domain.AppSettings

interface SettingsRepository {
    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}
