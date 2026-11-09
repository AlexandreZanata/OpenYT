package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.data.db.OpenYtDatabase
import java.io.File

fun createOpenYtDatabase(databasePath: String): OpenYtDatabase {
    val file = File(databasePath)
    file.parentFile?.mkdirs()
    val isNew = !file.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    if (isNew) {
        OpenYtDatabase.Schema.create(driver)
    }
    return OpenYtDatabase(driver)
}

fun defaultDatabasePath(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home") ?: "."
    val base = when {
        os.contains("win") -> System.getenv("APPDATA") ?: "$home/AppData/Roaming"
        os.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    return File(base, "OpenYT/openyt.db").absolutePath
}
