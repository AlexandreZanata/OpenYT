package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperation
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetOperationStatus
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightAssetOperationRepositoryTest {

    @Test
    fun schemaUpgradeFromV20ToV21PreservesExistingDataAndCreatesAssetOperationTable() = runBlocking {
        val file = File.createTempFile("openyt-v20-to-v21-", ".db").apply { delete() }
        try {
            createVersionTwentyDatabase(file)

            val database = createOpenYtDatabase(file.absolutePath)

            // Verify user version reached latest schema version
            val version = readUserVersion(file)
            assertEquals(OpenYtDatabase.Schema.version, version)

            // Verify existing v20 offline asset is preserved
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val existingAssets = assetRepo.loadAssetsForEntry("entry-v20")
            assertEquals(1, existingAssets.size)
            assertEquals("asset-v20", existingAssets.single().id)

            // Verify asset operation repository works on upgraded database
            val opRepo = SqlDelightAssetOperationRepository(database)
            val op = AssetOperation(
                id = "op-migrated-1",
                entryId = "entry-v20",
                kind = AssetKind.SUBTITLE,
                sourcePath = "/downloads/test.vtt",
                status = AssetOperationStatus.QUEUED,
                createdAtEpochMillis = 1000L,
                updatedAtEpochMillis = 1000L,
            )
            opRepo.insertOperation(op)

            val loaded = opRepo.getOperation("op-migrated-1")
            assertNotNull(loaded)
            assertEquals("op-migrated-1", loaded.id)
            assertEquals(AssetOperationStatus.QUEUED, loaded.status)
        } finally {
            file.delete()
        }
    }

    @Test
    fun crudAndActiveQueriesFunctionCorrectly() = runBlocking {
        val file = File.createTempFile("openyt-asset-op-crud-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val opRepo = SqlDelightAssetOperationRepository(database)

            val op1 = AssetOperation(
                id = "op-1",
                entryId = "entry-100",
                kind = AssetKind.ARTWORK,
                sourcePath = "/tmp/cover.jpg",
                language = null,
                sourceType = "THUMBNAIL",
                timelineBasis = null,
                isRequired = true,
                status = AssetOperationStatus.QUEUED,
                attempt = 0,
                createdAtEpochMillis = 1000L,
                updatedAtEpochMillis = 1000L,
            )

            val op2 = AssetOperation(
                id = "op-2",
                entryId = "entry-100",
                kind = AssetKind.SUBTITLE,
                sourcePath = "/tmp/sub.pt.vtt",
                language = "pt",
                sourceType = "SUBTITLE_VTT",
                timelineBasis = "SOURCE",
                isRequired = false,
                status = AssetOperationStatus.RUNNING,
                attempt = 0,
                createdAtEpochMillis = 2000L,
                updatedAtEpochMillis = 2000L,
            )

            opRepo.insertOperation(op1)
            opRepo.insertOperation(op2)

            // Load by id
            assertEquals(op1, opRepo.getOperation("op-1"))
            assertEquals(op2, opRepo.getOperation("op-2"))

            // Load active
            val active = opRepo.loadActiveOperations()
            assertEquals(2, active.size)

            // Find pending
            val pendingArt = opRepo.findPendingOperation("entry-100", AssetKind.ARTWORK, null, "/tmp/cover.jpg")
            assertNotNull(pendingArt)
            assertEquals("op-1", pendingArt.id)

            // Update status to SUCCEEDED
            val succeededOp = op1.copy(
                status = AssetOperationStatus.SUCCEEDED,
                targetLocator = "entry-100/asset-1.jpg",
                byteCount = 4096L,
                sha256 = "dummy-sha",
            )
            opRepo.updateOperation(succeededOp)
            assertEquals(AssetOperationStatus.SUCCEEDED, opRepo.getOperation("op-1")?.status)

            // Active operations now only op-2
            assertEquals(listOf("op-2"), opRepo.loadActiveOperations().map { it.id })

            // Mark running as interrupted
            opRepo.markRunningAsInterrupted()
            assertEquals(AssetOperationStatus.INTERRUPTED, opRepo.getOperation("op-2")?.status)
            assertTrue(opRepo.loadActiveOperations().isEmpty())

            // Delete operation
            opRepo.deleteOperation("op-1")
            assertNull(opRepo.getOperation("op-1"))

            // Delete operations for entry
            opRepo.deleteOperationsForEntry("entry-100")
            assertTrue(opRepo.loadOperationsForEntry("entry-100").isEmpty())
        } finally {
            file.delete()
        }
    }

    private fun readUserVersion(file: File): Long {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        return -1L
    }

    private fun createVersionTwentyDatabase(file: File) {
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
                statement.executeUpdate("PRAGMA user_version = 1")
            }
        }

        // Migrate to version 20
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OpenYtDatabase.Schema.migrate(driver, 1, 20)
        driver.close()

        // Insert version 20 data
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO offline_asset(
                        id, entry_id, kind, language, source_type, relative_locator, byte_count,
                        sha256, state, is_required, is_pinned, media_revision_size, media_revision_mtime,
                        media_revision_sha256, created_at, last_verified_at, failure_detail
                    ) VALUES (
                        'asset-v20', 'entry-v20', 'SUBTITLE', 'en', 'EXTRACTED', 'entry-v20/asset-v20.srt',
                        1024, 'dummy-sha-v20', 'VERIFIED', 1, 0, 5000, 1000, 'media-sha', 1000, 1000, NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 20")
            }
        }
    }
}
