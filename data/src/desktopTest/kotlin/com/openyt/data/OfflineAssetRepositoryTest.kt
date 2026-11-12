package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.ThemeMode
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

class OfflineAssetRepositoryTest {

    @Test
    fun schemaUpgradeFromV19ToV20PreservesExistingDataAndBackfillsAssetSettings() = runBlocking {
        val file = File.createTempFile("openyt-v19-to-v20-", ".db").apply { delete() }
        try {
            createVersionNineteenDatabase(file)

            val database = createOpenYtDatabase(file.absolutePath)
            val settingsRepo = SqlDelightSettingsRepository(database)
            val settings = settingsRepo.load()

            // Verify settings from schema 19 are preserved
            assertEquals(ThemeMode.DARK, settings.themeMode)
            assertEquals(AppLanguage.PT_BR, settings.language)
            assertEquals(2, settings.maxConcurrentDownloads)
            assertEquals("/home/user/Downloads", settings.downloadsDirectory)
            assertEquals("content://tree/v19", settings.storageTreeUri)
            assertEquals(DownloadProfile.BUILTIN_STUDY, settings.defaultProfileId)

            // Verify new v20 settings are backfilled with default values
            assertEquals(AppSettings.DEFAULT_ASSET_BUDGET_BYTES, settings.assetBudgetBytes)
            assertEquals(AppSettings.DEFAULT_STORAGE_RESERVE_BYTES, settings.storageReserveBytes)

            // Verify existing library entry preserved with unknown/null asset state
            val downloadRepo = SqlDelightDownloadRepository(database)
            val library = downloadRepo.loadLibrary()
            assertEquals(1, library.size)
            val entry = library.single()
            assertEquals("entry-v19", entry.id)
            assertEquals("Existing Video", entry.title)
            assertNull(entry.mediaRevision) // Old record with unknown asset state
            assertNull(entry.lastVerifiedEpochMillis)

            // Verify user version reached 20
            val version = readUserVersion(file)
            assertEquals(20L, version)

            // Verify offline asset repository functions on upgraded database
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val assets = assetRepo.loadAssetsForEntry("entry-v19")
            assertTrue(assets.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun reopeningDatabasePreservesOfflineAssetsAndRevisions() = runBlocking {
        val file = File.createTempFile("openyt-assets-reopen-", ".db").apply { delete() }
        try {
            val db1 = createOpenYtDatabase(file.absolutePath)
            val downloadRepo1 = SqlDelightDownloadRepository(db1)
            val assetRepo1 = SqlDelightOfflineAssetRepository(db1)

            val mediaRev = MediaRevision(
                sizeBytes = 1048576L,
                lastModifiedEpochMillis = 1700000000000L,
                contentSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            )
            val entry = LibraryEntry(
                id = "doc-42",
                title = "Offline Documentary",
                kind = MediaKind.VIDEO,
                filePath = "/storage/doc-42.mp4",
                sizeBytes = 1048576L,
                mediaRevision = mediaRev,
                lastVerifiedEpochMillis = 1700000005000L,
            )
            downloadRepo1.insertLibraryEntry(entry)

            val asset = OfflineAsset(
                id = "asset-sub-pt",
                entryId = "doc-42",
                kind = AssetKind.SUBTITLE,
                language = "pt",
                sourceType = "EXTRACTED",
                relativeLocator = "subtitles/doc-42.pt.srt",
                byteCount = 4096L,
                sha256 = "c246f48a1a364beaa970ec74d1a49f5062c31e6f6630f40d34ddb44ec0f8a846",
                state = AssetState.VERIFIED,
                isRequired = true,
                isPinned = true,
                mediaRevision = mediaRev,
                createdAtEpochMillis = 1700000001000L,
                lastVerifiedEpochMillis = 1700000005000L,
            )
            assetRepo1.insertAsset(asset)

            // Reopen database with fresh connection
            val db2 = createOpenYtDatabase(file.absolutePath)
            val downloadRepo2 = SqlDelightDownloadRepository(db2)
            val assetRepo2 = SqlDelightOfflineAssetRepository(db2)

            val restoredLibrary = downloadRepo2.loadLibrary()
            assertEquals(1, restoredLibrary.size)
            val restoredEntry = restoredLibrary.single()
            assertEquals("doc-42", restoredEntry.id)
            assertEquals(mediaRev, restoredEntry.mediaRevision)
            assertEquals(1700000005000L, restoredEntry.lastVerifiedEpochMillis)

            val restoredAssets = assetRepo2.loadAssetsForEntry("doc-42")
            assertEquals(1, restoredAssets.size)
            val restoredAsset = restoredAssets.single()
            assertEquals("asset-sub-pt", restoredAsset.id)
            assertEquals("doc-42", restoredAsset.entryId)
            assertEquals(AssetKind.SUBTITLE, restoredAsset.kind)
            assertEquals("pt", restoredAsset.language)
            assertEquals("EXTRACTED", restoredAsset.sourceType)
            assertEquals("subtitles/doc-42.pt.srt", restoredAsset.relativeLocator)
            assertEquals(4096L, restoredAsset.byteCount)
            assertEquals(AssetState.VERIFIED, restoredAsset.state)
            assertTrue(restoredAsset.isRequired)
            assertTrue(restoredAsset.isPinned)
            assertEquals(mediaRev, restoredAsset.mediaRevision)
        } finally {
            file.delete()
        }
    }

    @Test
    fun crudOperationsOnOfflineAssets() = runBlocking {
        val database = inMemoryDatabase()
        val downloadRepo = SqlDelightDownloadRepository(database)
        val assetRepo = SqlDelightOfflineAssetRepository(database)

        downloadRepo.insertLibraryEntry(
            LibraryEntry(id = "entry-crud", title = "CRUD Media", kind = MediaKind.VIDEO, filePath = "/media/crud.mp4")
        )

        val rev = MediaRevision(5000L, 1000L, "hash1")
        val asset1 = OfflineAsset(
            id = "asset-1",
            entryId = "entry-crud",
            kind = AssetKind.ARTWORK,
            relativeLocator = "covers/art1.jpg",
            byteCount = 500L,
            sha256 = "sha-art",
            state = AssetState.PENDING,
            isRequired = false,
            mediaRevision = rev,
        )
        val asset2 = OfflineAsset(
            id = "asset-2",
            entryId = "entry-crud",
            kind = AssetKind.SUBTITLE,
            language = "en",
            relativeLocator = "subs/sub.srt",
            byteCount = 800L,
            sha256 = "sha-sub",
            state = AssetState.PENDING,
            isRequired = true,
            mediaRevision = rev,
        )

        assetRepo.insertAsset(asset1)
        assetRepo.insertAsset(asset2)

        val loaded = assetRepo.loadAssetsForEntry("entry-crud")
        assertEquals(2, loaded.size)

        val loadedById = assetRepo.loadAssetById("asset-1")
        assertNotNull(loadedById)
        assertEquals("asset-1", loadedById.id)

        // Update state
        assetRepo.updateAssetState("asset-1", AssetState.VERIFIED, lastVerifiedEpochMillis = 9999L)
        val verified = assetRepo.loadAssetById("asset-1")
        assertNotNull(verified)
        assertEquals(AssetState.VERIFIED, verified.state)
        assertEquals(9999L, verified.lastVerifiedEpochMillis)

        // Update pin
        assetRepo.updateAssetPin("asset-2", isPinned = true)
        val pinned = assetRepo.loadAssetById("asset-2")
        assertNotNull(pinned)
        assertTrue(pinned.isPinned)

        // Delete asset
        assetRepo.deleteAsset("asset-1")
        assertNull(assetRepo.loadAssetById("asset-1"))
        assertEquals(1, assetRepo.loadAssetsForEntry("entry-crud").size)
    }

    @Test
    fun deletingLibraryEntryCascadesAndCleansOfflineAssetsWithoutTouchingFiles() = runBlocking {
        val database = inMemoryDatabase()
        val downloadRepo = SqlDelightDownloadRepository(database)
        val assetRepo = SqlDelightOfflineAssetRepository(database)

        val tempMedia = File.createTempFile("openyt-preserve-user-media-", ".mp4")
        tempMedia.writeText("Precious user media that must never be deleted!")

        try {
            val entry = LibraryEntry(
                id = "entry-cascade",
                title = "Important Media",
                kind = MediaKind.VIDEO,
                filePath = tempMedia.absolutePath,
            )
            downloadRepo.insertLibraryEntry(entry)

            val asset = OfflineAsset(
                id = "asset-cascade",
                entryId = "entry-cascade",
                kind = AssetKind.ARTWORK,
                relativeLocator = "covers/cover.jpg",
                byteCount = 100L,
                sha256 = "cover-sha",
                mediaRevision = MediaRevision(tempMedia.length(), tempMedia.lastModified()),
            )
            assetRepo.insertAsset(asset)

            assertEquals(1, assetRepo.loadAssetsForEntry("entry-cascade").size)

            // Delete library entry from repository
            downloadRepo.deleteLibraryEntry("entry-cascade")

            // DB records must be deleted
            assertTrue(downloadRepo.loadLibrary().isEmpty())
            assertTrue(assetRepo.loadAssetsForEntry("entry-cascade").isEmpty())

            // Physical user media must NOT be deleted!
            assertTrue(tempMedia.exists(), "User media file must NEVER be auto-deleted when library record is removed!")
            assertEquals("Precious user media that must never be deleted!", tempMedia.readText())
        } finally {
            tempMedia.delete()
        }
    }

    @Test
    fun invalidatingAssetsForEntryMarksThemStale() = runBlocking {
        val database = inMemoryDatabase()
        val downloadRepo = SqlDelightDownloadRepository(database)
        val assetRepo = SqlDelightOfflineAssetRepository(database)

        downloadRepo.insertLibraryEntry(
            LibraryEntry(id = "entry-stale", title = "Stale Media", kind = MediaKind.VIDEO, filePath = "/media/stale.mp4")
        )
        val rev = MediaRevision(100L, 200L)
        assetRepo.insertAsset(
            OfflineAsset(
                id = "art-stale",
                entryId = "entry-stale",
                kind = AssetKind.ARTWORK,
                relativeLocator = "art.jpg",
                byteCount = 10L,
                sha256 = "a",
                state = AssetState.VERIFIED,
                mediaRevision = rev,
            )
        )
        assetRepo.insertAsset(
            OfflineAsset(
                id = "sub-stale",
                entryId = "entry-stale",
                kind = AssetKind.SUBTITLE,
                relativeLocator = "sub.srt",
                byteCount = 20L,
                sha256 = "b",
                state = AssetState.VERIFIED,
                mediaRevision = rev,
            )
        )

        // Invalidate assets because media changed
        assetRepo.invalidateAssetsForEntry("entry-stale", AssetState.STALE)

        val assets = assetRepo.loadAssetsForEntry("entry-stale")
        assertEquals(2, assets.size)
        assertTrue(assets.all { it.state == AssetState.STALE })
    }

    @Test
    fun appSettingsPersistsCustomAssetBudgetAndStorageReserve() = runBlocking {
        val database = inMemoryDatabase()
        val repo = SqlDelightSettingsRepository(database)

        val customSettings = AppSettings(
            assetBudgetBytes = 1024L * 1024L * 1024L, // 1 GiB
            storageReserveBytes = 256L * 1024L * 1024L, // 256 MiB
        )
        repo.save(customSettings)

        val reloaded = repo.load()
        assertEquals(1024L * 1024L * 1024L, reloaded.assetBudgetBytes)
        assertEquals(256L * 1024L * 1024L, reloaded.storageReserveBytes)
    }

    @Test
    fun jvmMediaFileInspectorComputesAccurateDigestAndMetadata() = runBlocking {
        val tempFile = File.createTempFile("openyt-inspector-", ".txt")
        try {
            val content = "Hello OpenYT Offline Assets\n"
            tempFile.writeText(content)

            val inspector = JvmMediaFileInspector()
            assertTrue(inspector.exists(tempFile.absolutePath))

            val metadata = inspector.inspect(tempFile.absolutePath)
            assertNotNull(metadata)
            assertEquals(content.toByteArray().size.toLong(), metadata.sizeBytes)
            assertEquals(tempFile.lastModified(), metadata.lastModifiedEpochMillis)

            val sha256 = inspector.computeSha256(tempFile.absolutePath)
            assertNotNull(sha256)
            // Known sha256 of "Hello OpenYT Offline Assets\n"
            // Let's compute directly with MessageDigest to confirm
            val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedDigest, sha256)

            // Nonexistent file returns null / false
            assertFalse(inspector.exists("/nonexistent/file/path.xyz"))
            assertNull(inspector.inspect("/nonexistent/file/path.xyz"))
            assertNull(inspector.computeSha256("/nonexistent/file/path.xyz"))
        } finally {
            tempFile.delete()
        }
    }

    private fun inMemoryDatabase(): OpenYtDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        return OpenYtDatabase(driver)
    }

    private fun readUserVersion(file: File): Long =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    if (result.next()) result.getLong(1) else 0L
                }
            }
        }

    private fun createVersionNineteenDatabase(file: File) {
        // Create version 1 database
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

        // Migrate to version 19
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OpenYtDatabase.Schema.migrate(driver, 1, 19)
        driver.close()

        // Insert version 19 data
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO app_settings(
                        id, theme_mode, language, max_concurrent, downloads_directory,
                        storage_tree_uri, speed_limit_bytes_per_second, download_window_start_minutes,
                        download_window_end_minutes, organization_preset, file_organization_template,
                        clipboard_suggestions, download_notifications, default_profile_id
                    ) VALUES (
                        1, 'DARK', 'PT_BR', 2, '/home/user/Downloads',
                        'content://tree/v19', NULL, NULL,
                        NULL, 'PLAYLIST', NULL,
                        0, 0, 'builtin-study'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO library_entry(
                        id, title, author, kind, file_path, size_bytes, thumbnail_url, source
                    ) VALUES (
                        'entry-v19', 'Existing Video', 'Author', 'VIDEO', '/media/v19.mp4', 5000, NULL, 'youtube'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 19")
            }
        }
    }
}
