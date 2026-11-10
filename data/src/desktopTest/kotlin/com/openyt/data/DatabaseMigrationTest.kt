package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.data.db.OpenYtDatabase
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseMigrationTest {
    @Test
    fun migratesVersionSixAndBackfillsProviderSource() = runBlocking {
        val file = File.createTempFile("openyt-v6-", ".db").apply { delete() }
        try {
            createVersionSixDatabase(file)

            val database = createOpenYtDatabase(file.absolutePath)
            val repository = SqlDelightDownloadRepository(database)

            val library = repository.loadLibrary()
            assertEquals(1, library.size)
            assertEquals("youtube", library.single().source)

            val tasks = repository.loadTasks()
            assertEquals(1, tasks.size)
            assertEquals(DownloadPriority.NORMAL, tasks.single().priority)
            assertEquals(0, tasks.single().attempt)

            val settings = SqlDelightSettingsRepository(database).load()
            assertEquals(null, settings.speedLimitBytesPerSecond)
            assertEquals(null, settings.downloadWindowStartMinutes)
            assertEquals(null, settings.downloadWindowEndMinutes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun freshDatabaseRoundTripsProviderSource() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        val repository = SqlDelightDownloadRepository(OpenYtDatabase(driver))

        repository.insertLibraryEntry(
            LibraryEntry(
                id = "new-entry",
                title = "New",
                kind = MediaKind.AUDIO,
                filePath = "/tmp/new.m4a",
                source = "soundcloud",
            ),
        )

        assertEquals("soundcloud", repository.loadLibrary().single().source)
    }

    private fun createVersionSixDatabase(file: File) {
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
                        created_at INTEGER NOT NULL DEFAULT 0
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
                        created_at INTEGER NOT NULL DEFAULT 0,
                        thumbnail_url TEXT
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE app_settings (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        theme_mode TEXT NOT NULL DEFAULT 'SYSTEM',
                        language TEXT NOT NULL DEFAULT 'PT_BR',
                        max_concurrent INTEGER NOT NULL DEFAULT 2,
                        downloads_directory TEXT,
                        storage_tree_uri TEXT
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO download_task(
                        id, source_id, title, source, url, webpage_url,
                        kind, destination_directory, status
                    ) VALUES (
                        'download-1', 'abc', 'Sample', 'youtube',
                        'https://example.com/watch', 'https://example.com/watch',
                        'VIDEO', '/tmp', 'COMPLETED'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO library_entry(
                        id, title, kind, file_path, size_bytes, created_at, thumbnail_url
                    ) VALUES ('download-1', 'Sample', 'VIDEO', '/tmp/sample.mp4', 1, 0, NULL)
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 6")
            }
        }
    }
}
