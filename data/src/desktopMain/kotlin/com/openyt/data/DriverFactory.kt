package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.data.db.OpenYtDatabase
import java.io.File
import java.sql.DriverManager

fun createOpenYtDatabase(databasePath: String): OpenYtDatabase {
    val file = File(databasePath)
    file.parentFile?.mkdirs()
    val url = "jdbc:sqlite:${file.absolutePath}"
    val header = if (file.exists()) readDatabaseHeader(url) else DatabaseHeader(hasSchema = false, userVersion = 0L)
    val hasSchema = header.hasSchema
    val storedVersion = header.userVersion
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

private data class DatabaseHeader(
    val hasSchema: Boolean,
    val userVersion: Long,
)

private fun readDatabaseHeader(url: String): DatabaseHeader =
    DriverManager.getConnection(url).use { connection ->
        val hasSchema = connection.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        ).use { statement ->
            statement.setString(1, "download_task")
            statement.executeQuery().use { result -> result.next() }
        }
        val userVersion = if (hasSchema) {
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    if (result.next()) result.getLong(1) else 0L
                }
            }
        } else {
            0L
        }
        DatabaseHeader(hasSchema = hasSchema, userVersion = userVersion)
    }
