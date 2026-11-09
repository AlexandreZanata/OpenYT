package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.data.db.OpenYtDatabase
import java.io.File
import java.sql.DriverManager

fun createOpenYtDatabase(databasePath: String): OpenYtDatabase {
    val file = File(databasePath)
    file.parentFile?.mkdirs()
    val url = "jdbc:sqlite:${file.absolutePath}"
    val hasSchema = file.exists() && tableExists(url, "download_task")
    val storedVersion = if (hasSchema) readUserVersion(url) else 0L
    val target = OpenYtDatabase.Schema.version

    val driver = JdbcSqliteDriver(url)
    when {
        !hasSchema -> OpenYtDatabase.Schema.create(driver)

        storedVersion == 0L -> OpenYtDatabase.Schema.migrate(driver, 1, target)

        storedVersion < target ->
            OpenYtDatabase.Schema.migrate(driver, storedVersion, target)

        storedVersion > target ->
            error("database version $storedVersion is newer than supported $target")
    }

    if (!hasSchema || storedVersion != target) {
        driver.execute(null, "PRAGMA user_version = $target", 0)
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

private fun readUserVersion(url: String): Long =
    DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                if (result.next()) result.getLong(1) else 0L
            }
        }
    }

private fun tableExists(url: String, table: String): Boolean =
    DriverManager.getConnection(url).use { connection ->
        connection.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { result -> result.next() }
        }
    }
