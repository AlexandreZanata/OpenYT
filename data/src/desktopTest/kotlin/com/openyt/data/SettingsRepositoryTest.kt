package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.ThemeMode
import com.openyt.data.db.OpenYtDatabase
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {
    @Test
    fun returnsDefaultsWhenNoRowExists() = runBlocking {
        val repository = SqlDelightSettingsRepository(inMemoryDatabase())

        assertEquals(AppSettings(), repository.load())
    }

    @Test
    fun persistsAndReloadsSettings() = runBlocking {
        val database = inMemoryDatabase()
        val repository = SqlDelightSettingsRepository(database)

        repository.save(AppSettings(ThemeMode.DARK, AppLanguage.EN, 4, "/home/user/Videos"))

        assertEquals(
            AppSettings(ThemeMode.DARK, AppLanguage.EN, 4, "/home/user/Videos"),
            repository.load(),
        )
    }

    @Test
    fun migratesVersionOneDatabaseAndKeepsExistingData() = runBlocking {
        val file = File.createTempFile("openyt-v1-", ".db").apply { delete() }
        try {
            createVersionOneDatabase(file)

            val database = createOpenYtDatabase(file.absolutePath)
            val tasks = SqlDelightDownloadRepository(database).loadTasks()
            assertEquals(1, tasks.size)
            assertEquals("download-1", tasks.single().id)

            val settings = SqlDelightSettingsRepository(database)
            settings.save(AppSettings(ThemeMode.LIGHT, AppLanguage.EN, 3))
            assertEquals(3, settings.load().maxConcurrentDownloads)
            assertEquals(AppLanguage.EN, settings.load().language)

            // Reopening the migrated file must not run the migration again.
            val reopened = createOpenYtDatabase(file.absolutePath)
            assertEquals(1, SqlDelightDownloadRepository(reopened).loadTasks().size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun migratesVersionTwoDatabaseAndKeepsSettings() = runBlocking {
        val file = File.createTempFile("openyt-v2-", ".db").apply { delete() }
        try {
            createVersionTwoDatabase(file)

            val database = createOpenYtDatabase(file.absolutePath)
            val settings = SqlDelightSettingsRepository(database)
            val loaded = settings.load()
            assertEquals(ThemeMode.DARK, loaded.themeMode)
            assertEquals(AppLanguage.EN, loaded.language)
            assertEquals(3, loaded.maxConcurrentDownloads)
            assertEquals(null, loaded.downloadsDirectory)

            settings.save(loaded.copy(downloadsDirectory = "/data/media"))
            assertEquals("/data/media", settings.load().downloadsDirectory)
            assertEquals(1, SqlDelightDownloadRepository(database).loadTasks().size)
        } finally {
            file.delete()
        }
    }

    private fun inMemoryDatabase(): OpenYtDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        return OpenYtDatabase(driver)
    }

    private fun createVersionTwoDatabase(file: File) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE download_task (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        id TEXT NOT NULL UNIQUE,
                        source_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT,
                        source TEXT NOT NULL,
                        url TEXT NOT NULL,
                        webpage_url TEXT NOT NULL,
                        duration_seconds INTEGER,
                        thumbnail_url TEXT,
                        kind TEXT NOT NULL,
                        format_id TEXT,
                        destination_directory TEXT NOT NULL,
                        status TEXT NOT NULL,
                        percent REAL NOT NULL DEFAULT 0,
                        speed_bytes_per_second INTEGER,
                        eta_seconds INTEGER,
                        file_path TEXT,
                        size_bytes INTEGER,
                        failure_kind TEXT,
                        failure_detail TEXT,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE library_entry (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        size_bytes INTEGER,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE app_settings (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        theme_mode TEXT NOT NULL DEFAULT 'SYSTEM',
                        language TEXT NOT NULL DEFAULT 'PT_BR',
                        max_concurrent INTEGER NOT NULL DEFAULT 2
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO download_task(
                        id, source_id, title, source, url, webpage_url, kind,
                        destination_directory, status, percent
                    ) VALUES (
                        'download-1', 'abc', 'Old media', 'youtube',
                        'https://example.com/watch', 'https://example.com/watch',
                        'VIDEO', '/tmp/openyt', 'COMPLETED', 100
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO app_settings(id, theme_mode, language, max_concurrent)
                    VALUES (1, 'DARK', 'EN', 3)
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 2")
            }
        }
    }

    private fun createVersionOneDatabase(file: File) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE download_task (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        id TEXT NOT NULL UNIQUE,
                        source_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT,
                        source TEXT NOT NULL,
                        url TEXT NOT NULL,
                        webpage_url TEXT NOT NULL,
                        duration_seconds INTEGER,
                        thumbnail_url TEXT,
                        kind TEXT NOT NULL,
                        format_id TEXT,
                        destination_directory TEXT NOT NULL,
                        status TEXT NOT NULL,
                        percent REAL NOT NULL DEFAULT 0,
                        speed_bytes_per_second INTEGER,
                        eta_seconds INTEGER,
                        file_path TEXT,
                        size_bytes INTEGER,
                        failure_kind TEXT,
                        failure_detail TEXT,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE library_entry (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        size_bytes INTEGER,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO download_task(
                        id, source_id, title, source, url, webpage_url, kind,
                        destination_directory, status, percent
                    ) VALUES (
                        'download-1', 'abc', 'Old media', 'youtube',
                        'https://example.com/watch', 'https://example.com/watch',
                        'VIDEO', '/tmp/openyt', 'COMPLETED', 100
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 1")
            }
        }
    }
}
