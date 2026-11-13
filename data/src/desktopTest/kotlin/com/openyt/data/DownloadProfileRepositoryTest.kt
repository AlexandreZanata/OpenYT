package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.application.DestinationBindingStatus
import com.openyt.core.application.ProfileDestinationBinding
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ThemeMode
import com.openyt.data.db.OpenYtDatabase
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadProfileRepositoryTest {

    @Test
    fun upgradePreviousSchemaAndPreserveSettingsAndTasks() = runBlocking<Unit> {
        val file = File.createTempFile("openyt-v18-to-v19-", ".db").apply { delete() }
        try {
            createVersionEighteenDatabase(file)

            val database = createOpenYtDatabase(file.absolutePath)
            val settingsRepo = SqlDelightSettingsRepository(database)
            val settings = settingsRepo.load()

            // Verify settings from schema 18 are preserved
            assertEquals(ThemeMode.DARK, settings.themeMode)
            assertEquals(AppLanguage.EN, settings.language)
            assertEquals(3, settings.maxConcurrentDownloads)
            assertEquals("/home/user/Downloads", settings.downloadsDirectory)
            assertEquals("content://tree/v18", settings.storageTreeUri)
            assertEquals(true, settings.clipboardSuggestions)
            assertEquals(true, settings.downloadNotifications)
            assertNull(settings.defaultProfileId)

            // Verify queued tasks are preserved
            val downloadRepo = SqlDelightDownloadRepository(database)
            val tasks = downloadRepo.loadTasks()
            assertEquals(1, tasks.size)
            assertEquals("legacy-task-1", tasks.single().id)
            assertEquals("https://example.com/watch", tasks.single().request.url)

            // Verify PRAGMA user_version reaches current target schema
            val version = readUserVersion(file)
            assertEquals(OpenYtDatabase.Schema.version, version)

            // Verify profile repository functions on upgraded database
            val profileRepo = SqlDelightDownloadProfileRepository(database)
            val profiles = profileRepo.loadProfiles()
            assertEquals(4, profiles.size)
            assertEquals(DownloadProfile.BUILTIN_STUDY, profiles[0].id)
        } finally {
            file.delete()
        }
    }

    @Test
    fun seedsBuiltInProfilesAndEnforcesImmutability() = runBlocking<Unit> {
        val database = inMemoryDatabase()
        val repo = SqlDelightDownloadProfileRepository(database)

        val profiles = repo.loadProfiles()
        assertEquals(4, profiles.size)
        assertEquals(
            listOf(
                DownloadProfile.BUILTIN_STUDY,
                DownloadProfile.BUILTIN_AUDIO,
                DownloadProfile.BUILTIN_SAVE_SPACE,
                DownloadProfile.BUILTIN_TV,
            ),
            profiles.map { it.id },
        )

        // Built-ins cannot be deleted
        assertFalse(repo.deleteCustomProfile(DownloadProfile.BUILTIN_STUDY))
        assertFalse(repo.deleteCustomProfile(DownloadProfile.BUILTIN_AUDIO))
        assertEquals(4, repo.loadProfiles().size)

        // Attempting to overwrite a built-in profile throws IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            repo.saveCustomProfile(DownloadProfile.study().copy(isBuiltIn = false))
        }
        assertFailsWith<IllegalArgumentException> {
            repo.saveCustomProfile(DownloadProfile.AudioOnly)
        }
    }

    @Test
    fun persistsCustomProfilesAndDestinationBindingsAcrossReopen() = runBlocking<Unit> {
        val file = File.createTempFile("openyt-profile-reopen-", ".db").apply { delete() }
        try {
            val db1 = createOpenYtDatabase(file.absolutePath)
            val repo1 = SqlDelightDownloadProfileRepository(db1)

            val cloned = repo1.cloneProfile(DownloadProfile.AudioOnly, "my-audio", "My Audio")
            assertEquals("my-audio", cloned.id)
            assertEquals("My Audio", cloned.name)
            assertFalse(cloned.isBuiltIn)

            val customVideo = DownloadProfile(
                id = "custom-video",
                name = "High Quality Video",
                isBuiltIn = false,
                kind = MediaKind.VIDEO,
                maxHeight = 1080,
                preferMp4Avc = true,
            )
            repo1.saveCustomProfile(customVideo)

            repo1.setDefaultProfileId("my-audio")
            assertEquals("my-audio", repo1.getDefaultProfileId())

            repo1.saveDestinationBinding(
                ProfileDestinationBinding(
                    profileId = "my-audio",
                    destinationDirectory = "/home/user/Music",
                    storageTreeUri = null,
                ),
            )
            repo1.saveDestinationBinding(
                ProfileDestinationBinding(
                    profileId = DownloadProfile.BUILTIN_TV,
                    destinationDirectory = null,
                    storageTreeUri = "content://tree/tv",
                ),
            )

            // Reopen database
            val db2 = createOpenYtDatabase(file.absolutePath)
            val repo2 = SqlDelightDownloadProfileRepository(db2)

            val profiles = repo2.loadProfiles()
            assertEquals(6, profiles.size)
            val loadedCustom = repo2.getProfile("custom-video")
            assertNotNull(loadedCustom)
            assertEquals("High Quality Video", loadedCustom.name)
            assertEquals(1080, loadedCustom.maxHeight)

            assertEquals("my-audio", repo2.getDefaultProfileId())

            val audioBinding = repo2.getDestinationBinding("my-audio")
            assertNotNull(audioBinding)
            assertEquals("/home/user/Music", audioBinding.destinationDirectory)
            assertNull(audioBinding.storageTreeUri)

            val tvBinding = repo2.getDestinationBinding(DownloadProfile.BUILTIN_TV)
            assertNotNull(tvBinding)
            assertNull(tvBinding.destinationDirectory)
            assertEquals("content://tree/tv", tvBinding.storageTreeUri)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsDuplicateNamesAndDuplicateIds() = runBlocking<Unit> {
        val database = inMemoryDatabase()
        val repo = SqlDelightDownloadProfileRepository(database)
        repo.loadProfiles()

        // Collides with built-in profile name (case-insensitive)
        assertFailsWith<IllegalArgumentException> {
            repo.saveCustomProfile(
                DownloadProfile(
                    id = "custom-audio",
                    name = "Audio only",
                    isBuiltIn = false,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            repo.saveCustomProfile(
                DownloadProfile(
                    id = "custom-tv",
                    name = "WATCH ON TV",
                    isBuiltIn = false,
                ),
            )
        }

        // Create first custom profile
        repo.saveCustomProfile(
            DownloadProfile(
                id = "custom-podcast",
                name = "Podcasts",
                isBuiltIn = false,
            ),
        )

        // Collides with existing custom profile name
        assertFailsWith<IllegalArgumentException> {
            repo.saveCustomProfile(
                DownloadProfile(
                    id = "custom-podcast-2",
                    name = "Podcasts",
                    isBuiltIn = false,
                ),
            )
        }

        // Updating the same profile with unchanged name succeeds
        repo.saveCustomProfile(
            DownloadProfile(
                id = "custom-podcast",
                name = "Podcasts",
                isBuiltIn = false,
                audioPreset = AudioPreset.MP3,
            ),
        )
        assertEquals(AudioPreset.MP3, repo.getProfile("custom-podcast")?.audioPreset)
    }

    @Test
    fun handlesCorruptedProfileDefinitionsGracefully() = runBlocking<Unit> {
        val database = inMemoryDatabase()
        val queries = database.downloadQueries

        // Insert corrupt JSON directly
        queries.insertProfile(
            id = "corrupt-1",
            name = "Broken Json",
            is_builtin = 0L,
            definition_json = "{bad-json: broken",
        )

        // Insert unsupported schema version
        queries.insertProfile(
            id = "corrupt-2",
            name = "Future Schema",
            is_builtin = 0L,
            definition_json = """{"schemaVersion": 9999, "profile": {"id": "corrupt-2", "name": "Future Schema"}}""",
        )

        val repo = SqlDelightDownloadProfileRepository(database)
        val profiles = repo.loadProfiles()

        // Corrupt profiles are safely omitted from results
        assertEquals(4, profiles.size)
        assertNull(repo.getProfile("corrupt-1"))
        assertNull(repo.getProfile("corrupt-2"))
    }

    @Test
    fun detectsMissingDestinationPermission() {
        val repo = SqlDelightDownloadProfileRepository(inMemoryDatabase())

        val validBinding = ProfileDestinationBinding("p1", destinationDirectory = "/valid/path")
        val missingBinding = ProfileDestinationBinding("p2", destinationDirectory = "/no/perm")
        val emptyBinding = ProfileDestinationBinding("p3")

        val checker = { dir: String?, _: String? -> dir == "/valid/path" }

        assertEquals(
            DestinationBindingStatus.VALID,
            repo.validateDestinationAccess(validBinding, checker),
        )
        assertEquals(
            DestinationBindingStatus.PERMISSION_DENIED,
            repo.validateDestinationAccess(missingBinding, checker),
        )
        assertEquals(
            DestinationBindingStatus.NO_BINDING,
            repo.validateDestinationAccess(emptyBinding, checker),
        )
        assertEquals(
            DestinationBindingStatus.NO_BINDING,
            repo.validateDestinationAccess(null, checker),
        )
    }

    @Test
    fun deletedProfileDoesNotInvalidateQueuedTasks() = runBlocking<Unit> {
        val file = File.createTempFile("openyt-profile-task-independence-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val profileRepo = SqlDelightDownloadProfileRepository(database)
            val downloadRepo = SqlDelightDownloadRepository(database)

            val custom = DownloadProfile(
                id = "custom-delete-me",
                name = "Temporary Profile",
                isBuiltIn = false,
            )
            profileRepo.saveCustomProfile(custom)
            profileRepo.setDefaultProfileId("custom-delete-me")
            profileRepo.saveDestinationBinding(
                ProfileDestinationBinding("custom-delete-me", destinationDirectory = "/tmp/custom"),
            )

            // Enqueue task referencing the profile
            val task = DownloadTask(
                id = "task-profile-ref-1",
                media = com.openyt.core.domain.MediaInfo(
                    sourceId = "item-1",
                    source = "youtube",
                    title = "Sample media",
                    webpageUrl = "https://example.com/item/1",
                ),
                request = com.openyt.core.domain.DownloadRequest(
                    url = "https://example.com/item/1",
                    destinationDirectory = "/tmp/custom",
                    kind = MediaKind.VIDEO,
                ),
            )
            downloadRepo.insertTask(task)

            // Delete the custom profile
            assertTrue(profileRepo.deleteCustomProfile("custom-delete-me"))
            assertNull(profileRepo.getProfile("custom-delete-me"))
            assertNull(profileRepo.getDestinationBinding("custom-delete-me"))
            assertNull(profileRepo.getDefaultProfileId())

            // Task remains intact in download_task
            val tasks = downloadRepo.loadTasks()
            assertEquals(1, tasks.size)
            assertEquals("task-profile-ref-1", tasks.single().id)
            assertEquals("https://example.com/item/1", tasks.single().request.url)
        } finally {
            file.delete()
        }
    }

    @Test
    fun portableProfileExportExcludesLocalBindingsAndSecrets() {
        val repo = SqlDelightDownloadProfileRepository(inMemoryDatabase())
        val profile = DownloadProfile.study("pt")

        val exported = repo.exportProfileDefinition(profile)

        // Excludes secrets, filesystem paths, and URI markers
        assertFalse(exported.contains("/home/"))
        assertFalse(exported.contains("content://"))
        assertFalse(exported.contains("cookie"))
        assertFalse(exported.contains("token"))

        // Round-trip import
        val imported = repo.importProfileDefinition(exported)
        assertTrue(imported.isSuccess)
        assertEquals(profile.id, imported.getOrThrow().id)
        assertEquals(profile.name, imported.getOrThrow().name)
        assertEquals(profile.maxHeight, imported.getOrThrow().maxHeight)

        // Corrupted import returns failure
        val failed = repo.importProfileDefinition("not json at all")
        assertTrue(failed.isFailure)
    }

    private fun inMemoryDatabase(): OpenYtDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        return OpenYtDatabase(driver)
    }

    private fun createVersionEighteenDatabase(file: File) {
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

        // Migrate to version 18
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OpenYtDatabase.Schema.migrate(driver, 1, 18)
        driver.close()

        // Insert version 18 data
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO app_settings(
                        id, theme_mode, language, max_concurrent, downloads_directory,
                        storage_tree_uri, speed_limit_bytes_per_second, download_window_start_minutes,
                        download_window_end_minutes, organization_preset, file_organization_template,
                        clipboard_suggestions, download_notifications
                    ) VALUES (
                        1, 'DARK', 'EN', 3, '/home/user/Downloads',
                        'content://tree/v18', NULL, NULL,
                        NULL, 'PLAYLIST', NULL,
                        1, 1
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO download_task(
                        id, source_id, title, author, source, url, webpage_url, duration_seconds,
                        thumbnail_url, kind, format_id, destination_directory, status, percent,
                        created_at, priority, attempt, file_name_template, options_json
                    ) VALUES (
                        'legacy-task-1', 'source-1', 'Legacy Media', 'Author', 'youtube', 'https://example.com/watch', 'https://example.com/watch', 120,
                        NULL, 'VIDEO', 'best', '/home/user/Downloads', 'COMPLETED', 100,
                        1000, 'NORMAL', 0, '%(title)s.%(ext)s', '{}'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 18")
            }
        }
    }

    private fun readUserVersion(file: File): Long =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    if (result.next()) result.getLong(1) else 0L
                }
            }
        }
}
