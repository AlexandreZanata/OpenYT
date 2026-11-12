package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.StudyService
import com.openyt.core.application.StudyValidator
import com.openyt.core.domain.Collection
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyFailure
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyResult
import com.openyt.core.domain.StudyValidationError
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SQLDelight and SQLite migration integration tests covering NX-T01:
 * - v23 -> v24 migration and database reopen survival;
 * - Unicode / code-point length limits;
 * - Overlapping intervals and deterministic same-time ordering;
 * - Missing entry rejection and non-cascading survival;
 * - Path relocation preservation vs content revision staleness;
 * - Collection membership changes and shared membership;
 * - Explicit manual study completion independent of playback end reset;
 * - Record-removal preview semantics preserving authored notes and original media.
 */
class SqlDelightStudyRepositoryTest {

    @Test
    fun schemaUpgradeFromV23ToV24PreservesExistingDataAndReopensStudyRecords() = runBlocking {
        val file = File.createTempFile("openyt-v23-to-v24-", ".db").apply { delete() }
        try {
            createVersionTwentyThreeDatabase(file)

            // Upgrade via createOpenYtDatabase (runs 23.sqm)
            val db1 = createOpenYtDatabase(file.absolutePath)
            assertEquals(OpenYtDatabase.Schema.version, readUserVersion(file))
            assertTrue(readUserVersion(file) >= 24L)

            val downloadRepo1 = SqlDelightDownloadRepository(db1)
            val playbackStore1 = SqlDelightPlaybackStore(db1)
            val studyRepo1 = SqlDelightStudyRepository(db1)

            // Verify existing v23 data survived intact
            val loadedEntries = downloadRepo1.loadLibrary()
            assertEquals(1, loadedEntries.size)
            assertEquals("entry-v23", loadedEntries.single().id)
            assertEquals("Existing V23 Lecture", loadedEntries.single().title)

            val existingPos = playbackStore1.position("/media/v23.mp4", "entry-v23")
            assertNotNull(existingPos)
            assertEquals(42_000L, existingPos.positionMillis)

            // Persist study records with deterministic clock & Unicode content
            var now = 1_700_000_010_000L
            var seq = 0
            val service1 = StudyService(
                repository = studyRepo1,
                nowEpochMillis = { now },
                nextId = { "study-v24-${++seq}" },
            )

            val note = assertIs<StudyResult.Success<StudyNote>>(
                service1.createNote(
                    entryId = "entry-v23",
                    timestampMillis = 15_000L,
                    endMillis = 30_000L,
                    body = "Nota bilíngue 📖 line 1\n日本語テスト & acentuação",
                ),
            ).value

            now += 5_000L
            val bookmark = assertIs<StudyResult.Success<StudyBookmark>>(
                service1.createBookmark("entry-v23", 60_000L, "Definição Central 🔖"),
            ).value

            now += 5_000L
            val range = assertIs<StudyResult.Success<*>>(
                service1.saveRange("entry-v23", 10_000L, 25_000L, "Segmento difícil"),
            )
            assertNotNull(range.value)

            now += 5_000L
            service1.setEntryStudied("entry-v23", true)

            // Reopen database fresh from disk
            val db2 = createOpenYtDatabase(file.absolutePath)
            val studyRepo2 = SqlDelightStudyRepository(db2)
            val bundle = studyRepo2.loadEntryStudyBundle("entry-v23")

            assertTrue(bundle.entryExists)
            assertEquals(1, bundle.notes.size)
            assertEquals(note, bundle.notes.single())
            assertEquals(1, bundle.bookmarks.size)
            assertEquals(bookmark, bundle.bookmarks.single())
            assertEquals(1, bundle.savedRanges.size)
            assertTrue(bundle.studyState.isStudied)
            assertEquals(now, bundle.studyState.studiedAtEpochMillis)
            assertFalse(bundle.hasStaleAnchors)

            // Verify collection progress survived reopen
            val colProgress = studyRepo2.collectionProgress("col-v23")
            assertEquals(1, colProgress.studiedCount)
            assertEquals(1, colProgress.totalMemberCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun overlappingNotesSameTimeOrderingAndUnicodeLimitsRoundTripOnSqlite() = runBlocking {
        val file = File.createTempFile("openyt-study-order-", ".db").apply { delete() }
        try {
            val db = createOpenYtDatabase(file.absolutePath)
            val downloadRepo = SqlDelightDownloadRepository(db)
            val studyRepo = SqlDelightStudyRepository(db)

            downloadRepo.insertLibraryEntry(
                LibraryEntry("e-order", "Order Test", MediaKind.AUDIO, "/tmp/audio.m4a"),
            )

            val fixedIds = ArrayDeque(listOf("id-3", "id-1", "id-2", "id-4", "id-unicode", "bm-2", "bm-1"))
            var now = 1_000L
            val service = StudyService(
                repository = studyRepo,
                nowEpochMillis = { now },
                nextId = { fixedIds.removeFirst() },
            )

            // Overlapping intervals and identical timestamps inserted out of order
            now = 300L
            service.createNote("e-order", 10_000L, "Same start later clock")
            now = 200L
            service.createNote("e-order", 10_000L, "Same start + clock lex first")
            now = 200L
            service.createNote("e-order", 10_000L, "Same start + clock lex second")
            now = 100L
            service.createNote("e-order", 10_000L, "Overlapping 10s..18s", endMillis = 18_000L)

            // Max 20,000 Unicode chars accepted; 20,001 rejected
            val twentyK = "🎓" + "a".repeat(StudyValidator.MAX_NOTE_BODY_CHARS - 1)
            assertEquals(StudyValidator.MAX_NOTE_BODY_CHARS, StudyValidator.unicodeCodePointCount(twentyK))
            assertIs<StudyResult.Success<StudyNote>>(service.createNote("e-order", 50_000L, twentyK))

            val rejectedLong = assertIs<StudyResult.Failure>(
                service.createNote("e-order", 50_000L, twentyK + "x"),
            ).failure as StudyFailure.Validation
            assertEquals(StudyValidationError.NOTE_BODY_TOO_LONG, rejectedLong.error)

            // Same-time bookmarks
            now = 500L
            service.createBookmark("e-order", 4_000L, "Later BM")
            now = 400L
            service.createBookmark("e-order", 4_000L, "Earlier BM")

            val reloaded = SqlDelightStudyRepository(createOpenYtDatabase(file.absolutePath)).loadEntryStudyBundle("e-order")
            assertEquals(
                listOf("id-1", "id-2", "id-3", "id-4", "id-unicode"),
                reloaded.notes.map { it.id },
            )
            assertEquals(twentyK, reloaded.notes.last().body)
            assertEquals(listOf("bm-1", "bm-2"), reloaded.bookmarks.map { it.id })
        } finally {
            file.delete()
        }
    }

    @Test
    fun pathUpdatesAndEntryReplacementsPreserveNotesWhileContentRevisionChangeFlagsStale() = runBlocking {
        val file = File.createTempFile("openyt-study-rev-", ".db").apply { delete() }
        try {
            val db = createOpenYtDatabase(file.absolutePath)
            val downloadRepo = SqlDelightDownloadRepository(db)
            val studyRepo = SqlDelightStudyRepository(db)
            var now = 20_000L
            var seq = 0
            val service = StudyService(studyRepo, nowEpochMillis = { now }, nextId = { "n-${++seq}" })

            val rev1 = MediaRevision(sizeBytes = 50_000L, lastModifiedEpochMillis = 10_000L, contentSha256 = "sha-rev-1")
            val entry = LibraryEntry(
                id = "e-reloc",
                title = "Relocatable Video",
                kind = MediaKind.VIDEO,
                filePath = "/old/folder/video.mp4",
                sizeBytes = 50_000L,
                mediaRevision = rev1,
            )
            downloadRepo.insertLibraryEntry(entry)

            val note1 = assertIs<StudyResult.Success<StudyNote>>(
                service.createNote("e-reloc", 12_000L, "Authored at rev1"),
            ).value
            service.createBookmark("e-reloc", 20_000L, "Bookmark rev1")
            service.saveRange("e-reloc", 5_000L, 9_000L, "Range rev1")
            service.setEntryStudied("e-reloc", true)

            // 1. Move locator path -> no loss, NOT stale
            downloadRepo.updateLibraryPath("e-reloc", "/new/ssd/moved-video.mp4")
            assertFalse(studyRepo.loadEntryStudyBundle("e-reloc").hasStaleAnchors)
            assertEquals("Authored at rev1", studyRepo.loadNotesForEntry("e-reloc").single().body)

            // 2. Replace library entry row via INSERT OR REPLACE -> NO silent SQLite cascade loss!
            downloadRepo.insertLibraryEntry(entry.copy(filePath = "/relinked/ssd/video.mp4"))
            val afterReplace = studyRepo.loadEntryStudyBundle("e-reloc")
            assertEquals(1, afterReplace.notes.size)
            assertEquals(1, afterReplace.bookmarks.size)
            assertEquals(1, afterReplace.savedRanges.size)
            assertTrue(afterReplace.studyState.isStudied)
            assertFalse(afterReplace.hasStaleAnchors)

            // 3. Content revision changes -> flags existing anchors as potentially stale
            val rev2 = MediaRevision(sizeBytes = 48_000L, lastModifiedEpochMillis = 99_000L, contentSha256 = "sha-rev-2")
            downloadRepo.updateLibraryRevision("e-reloc", rev2, 99_000L)

            now = 30_000L
            val freshNote = assertIs<StudyResult.Success<StudyNote>>(
                service.createNote("e-reloc", 3_000L, "Fresh on rev2"),
            ).value

            val bundleStale = studyRepo.loadEntryStudyBundle("e-reloc")
            assertTrue(bundleStale.hasStaleAnchors)
            assertEquals(setOf(note1.id), bundleStale.staleNoteIds)
            assertEquals(1, bundleStale.staleBookmarkIds.size)
            assertEquals(1, bundleStale.staleRangeIds.size)
            assertFalse(freshNote.id in bundleStale.staleNoteIds)
        } finally {
            file.delete()
        }
    }

    @Test
    fun collectionMembershipCompletionIndependenceAndRemovalPreviewPreserveMediaAndAuthoredText() = runBlocking {
        val file = File.createTempFile("openyt-study-col-", ".db").apply { delete() }
        val mediaFile = File.createTempFile("openyt-study-user-media-", ".mp4").apply {
            writeText("Original user media bytes must never be deleted")
        }
        try {
            val db = createOpenYtDatabase(file.absolutePath)
            val downloadRepo = SqlDelightDownloadRepository(db)
            val playbackStore = SqlDelightPlaybackStore(db)
            val studyRepo = SqlDelightStudyRepository(db)
            var now = 5_000L
            var seq = 0
            val service = StudyService(studyRepo, nowEpochMillis = { now }, nextId = { "item-${++seq}" })

            downloadRepo.insertLibraryEntry(
                LibraryEntry("e1", "Entry 1", MediaKind.VIDEO, mediaFile.absolutePath),
            )
            downloadRepo.insertLibraryEntry(
                LibraryEntry("e2", "Entry 2", MediaKind.VIDEO, "/tmp/e2.mp4"),
            )
            downloadRepo.insertCollection(Collection("col-1", "Course A"))
            downloadRepo.insertCollection(Collection("col-2", "Course B"))
            downloadRepo.addEntryToCollection("col-1", "e1")
            downloadRepo.addEntryToCollection("col-1", "e2")
            downloadRepo.addEntryToCollection("col-2", "e1")

            // Reject missing entry cleanly
            val missing = service.createNote("missing-id", 0L, "Nope")
            assertIs<StudyFailure.EntryNotFound>(assertIs<StudyResult.Failure>(missing).failure)

            // Mark e1 studied + author note
            service.setEntryStudied("e1", true)
            service.createNote("e1", 2_000L, "Note to review before removing")

            // Shared membership across collections
            val allBefore = service.allCollectionProgress()
            assertEquals(1, allBefore["col-1"]?.studiedCount)
            assertEquals(2, allBefore["col-1"]?.totalMemberCount)
            assertEquals(1, allBefore["col-2"]?.studiedCount)
            assertEquals(1, allBefore["col-2"]?.totalMemberCount)

            // Playback near the end resets resumeMillis -> must NOT erase manual study completion
            playbackStore.savePosition(
                mediaFile.absolutePath,
                PlaybackCheckpoint(positionMillis = 98_500L, durationMillis = 100_000L, entryId = "e1"),
                "e1",
            )
            assertEquals(0L, playbackStore.position(mediaFile.absolutePath, "e1")?.resumeMillis)
            assertTrue(studyRepo.loadEntryStudyState("e1").isStudied)

            // Remove e1 from col-1 -> col-1 becomes 0/1, col-2 keeps 1/1
            downloadRepo.removeEntryFromCollection("col-1", "e1")
            val afterColRemove = service.allCollectionProgress()
            assertEquals(0, afterColRemove["col-1"]?.studiedCount)
            assertEquals(1, afterColRemove["col-1"]?.totalMemberCount)
            assertEquals(1, afterColRemove["col-2"]?.studiedCount)
            assertEquals(1, afterColRemove["col-2"]?.totalMemberCount)

            // Preview removal captures authored note and studied state cleanly
            val preview = service.previewEntryRemoval("e1")
            assertEquals("Entry 1", preview.entryTitle)
            assertTrue(preview.entryExistsInLibrary)
            assertEquals(1, preview.noteCount)
            assertTrue(preview.isMarkedStudied)
            assertTrue(preview.requiresConfirmationPrompt)
            assertTrue(preview.originalMediaPreserved)

            // Even if deleteLibraryEntry is invoked, authored text is NOT silently cascaded away
            downloadRepo.deleteLibraryEntry("e1")
            val preservedPreview = service.previewEntryRemoval("e1")
            assertFalse(preservedPreview.entryExistsInLibrary)
            assertEquals(1, preservedPreview.noteCount)
            assertEquals("Note to review before removing", preservedPreview.notes.single().body)
            assertEquals(0, studyRepo.collectionProgress("col-2").totalMemberCount)

            // Explicit study data deletion clears study rows without touching user media file
            service.deleteAllStudyDataForEntry("e1")
            assertEquals(0, studyRepo.previewEntryRemoval("e1").noteCount)
            assertTrue(mediaFile.exists())
            assertEquals("Original user media bytes must never be deleted", mediaFile.readText())
        } finally {
            file.delete()
            mediaFile.delete()
        }
    }

    private fun createVersionTwentyThreeDatabase(file: File) {
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

        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OpenYtDatabase.Schema.migrate(driver, 1, 23)
        driver.close()

        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO library_entry(id, title, author, kind, file_path, size_bytes)
                    VALUES ('entry-v23', 'Existing V23 Lecture', 'Prof. Test', 'VIDEO', '/media/v23.mp4', 8192)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO collection(id, name) VALUES ('col-v23', 'Existing V23 Collection')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO collection_entry(collection_id, entry_id) VALUES ('col-v23', 'entry-v23')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO playback_position(path, position_millis, duration_millis, entry_id)
                    VALUES ('/media/v23.mp4', 42000, 120000, 'entry-v23')
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 23")
            }
        }
    }

    private fun readUserVersion(file: File): Long =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
}
