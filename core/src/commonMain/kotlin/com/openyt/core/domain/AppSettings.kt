package com.openyt.core.domain

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class AppLanguage {
    PT_BR,
    EN,
}

data class DownloadWindow(
    val startMinutes: Int,
    val endMinutes: Int,
) {
    fun allows(minuteOfDay: Int): Boolean {
        val minute = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return when {
            startMinutes < endMinutes -> minute in startMinutes until endMinutes
            startMinutes > endMinutes -> minute >= startMinutes || minute < endMinutes
            else -> false
        }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

enum class OrganizationPreset {
    PLAYLIST,
    PLAYLIST_CHANNEL,
    PLAYLIST_YEAR,
    CHANNEL,
    YEAR,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.PT_BR,
    val maxConcurrentDownloads: Int = DEFAULT_CONCURRENT,
    val downloadsDirectory: String? = null,
    val storageTreeUri: String? = null,
    val speedLimitBytesPerSecond: Long? = null,
    val downloadWindowStartMinutes: Int? = null,
    val downloadWindowEndMinutes: Int? = null,
    val organizationPreset: OrganizationPreset = OrganizationPreset.PLAYLIST,
    val fileOrganizationTemplate: String? = null,
) {
    companion object {
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 4
        const val DEFAULT_CONCURRENT = 2
    }
}
