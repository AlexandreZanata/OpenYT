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

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.PT_BR,
    val maxConcurrentDownloads: Int = DEFAULT_CONCURRENT,
    val downloadsDirectory: String? = null,
) {
    companion object {
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 4
        const val DEFAULT_CONCURRENT = 2
    }
}
