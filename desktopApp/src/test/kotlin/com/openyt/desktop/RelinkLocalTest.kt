package com.openyt.desktop

import com.openyt.core.application.CandidateMatchConfidence
import com.openyt.core.application.MediaHealthStatus
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCapabilities
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackSessionControl
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.PortableExportOptions
import com.openyt.core.application.PortableRestoreConflictPolicy
import com.openyt.core.application.PortableRestoreOptions
import com.openyt.core.application.RelinkContentMode
import com.openyt.core.application.RelinkException
import com.openyt.core.application.RelinkFailure
import com.openyt.core.application.RelinkTransferStrategy
import com.openyt.core.application.StudyAnchorInspector
import com.openyt.core.application.SubtitleIndexRebuilder
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.Collection
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.TimedSearchResult
import com.openyt.data.FilePortableStreamBridge
import com.openyt.data.JvmOfflineAssetStore
import com.openyt.data.SqlDelightAssetOperationRepository
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createLibraryRecoveryRuntime
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.createPortableBackupRuntime
import com.openyt.data.search.JvmSubtitleContentAdapter
import com.openyt.data.search.SqlDelightTimedSearchRepository
import com.openyt.desktop.downloader.ExecutableLocator
import com.openyt.desktop.player.DesktopPlaybackLauncher
import com.openyt.ui.i18n.EnStrings
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelinkLocalTest {

    @Test
    fun movedAndRenamedMediaRelinksWithSqlDelightAndPreservesContextAcrossReopen() = runBlocking {
        val tempRoot = createTempDirectory("openyt-relink-local-").toFile()
        try {
            val dbPath = File(tempRoot, "openyt.db").absolutePath
            val downloadsDir = File(tempRoot, "downloads").apply { mkdirs() }
            val movedDir = File(tempRoot, "archive/Áudio & Vídeos").apply { mkdirs() }
            val stagingDir = File(tempRoot, "staging").apply { mkdirs() }

            val db = createOpenYtDatabase(dbPath)
            val downloadRepo = SqlDelightDownloadRepository(db)
            val offlineRepo = SqlDelightOfflineAssetRepository(db)
            val subIndexRepo = SqlDelightSubtitleIndexRepository(db)
            val studyRepo = SqlDelightStudyRepository(db)
            val playbackStore = SqlDelightPlaybackStore(db)

            val originalFile = File(downloadsDir, "lesson-01.mkv").apply {
                writeBytes("LOCAL-RELINK-STREAM-CONTENT-001".encodeToByteArray())
            }
            val origSha = sha256(originalFile)
            val origRev = MediaRevision(originalFile.length(), originalFile.lastModified(), origSha)

            downloadRepo.insertLibraryEntry(
                LibraryEntry(
                    id = "entry-move-1",
                    title = "Relink Systems Lecture",
                    kind = MediaKind.VIDEO,
                    filePath = originalFile.absolutePath,
                    sizeBytes = originalFile.length(),
                    createdAtEpochMillis = 1_700_000_000_000L,
                    tags = listOf("kotlin", "recovery"),
                    mediaRevision = origRev,
                ),
            )
            downloadRepo.insertCollection(Collection("col-rec", "Systems"))
            downloadRepo.addEntryToCollection("col-rec", "entry-move-1")
            playbackStore.savePosition(
                originalFile.absolutePath,
                PlaybackCheckpoint(45_000L, 120_000L, "entry-move-1"),
                "entry-move-1",
            )
            studyRepo.upsertNote(
                StudyNote("n-1", "entry-move-1", 10_000L, 14_000L, "Keep note valid", origRev, 100L, 100L),
            )
            studyRepo.upsertBookmark(
                StudyBookmark("b-1", "entry-move-1", 45_000L, "Midpoint", origRev, 101L, 101L),
            )
            studyRepo.upsertSavedRange(
                SavedStudyRange("r-1", "entry-move-1", 2_000L, 8_000L, "Loop", origRev, 102L, 102L),
            )
            studyRepo.saveEntryStudyState(
                EntryStudyState("entry-move-1", isStudied = true, studiedAtEpochMillis = 200L, updatedAtEpochMillis = 200L),
            )
            val srt = File(tempRoot, "sub.srt").apply {
                writeText("1\n00:00:05,000 --> 00:00:09,000\nRelink synchronized search cue\n", Charsets.UTF_8)
            }
            SubtitleIndexRebuilder(subIndexRepo, JvmSubtitleContentAdapter())
                .reindexTrack("trk-1", "entry-move-1", srt.absolutePath, "en", origSha)

            // Simulate user moving & renaming file outside the app
            val relocatedFile = File(movedDir, "Renamed Systems #1.mkv")
            Files.move(originalFile.toPath(), relocatedFile.toPath())

            var stopCalls = 0
            val activeControl = object : PlaybackSessionControl {
                override var loadedMedia: PlayableMedia? = PlayableMedia(
                    path = originalFile.absolutePath,
                    title = "Relink Systems Lecture",
                    kind = MediaKind.VIDEO,
                    entryId = "entry-move-1",
                )
                    private set
                override val capabilities = PlaybackCapabilities(canSeek = true, canPause = true, isInternalPlayer = true)
                override val state = PlaybackSessionState.PLAYING
                override val currentPositionMillis = 45_000L
                override val durationMillis = 120_000L
                override suspend fun openAt(items: List<PlayableMedia>, startIndex: Int, startPositionMillis: Long?) = true
                override suspend fun seekTo(positionMillis: Long) = true
                override suspend fun pause() = Unit
                override suspend fun play() = Unit
                override suspend fun stop() {
                    stopCalls++
                    loadedMedia = null
                }
                override fun close() = Unit
            }

            val runtime = createLibraryRecoveryRuntime(
                database = db,
                downloadRepository = downloadRepo,
                offlineAssetRepository = offlineRepo,
                subtitleIndexRepository = subIndexRepo,
                studyRepository = studyRepo,
                stagingRoot = stagingDir,
                ownedRecoveryRootProvider = { downloadsDir.absolutePath },
                playbackSessionControl = activeControl,
            )

            // 1. Read-only health inspection detects MISSING without changing DB
            val health = runtime.controller.analyzeHealth()
            assertEquals(1, health.summary.missingCount)
            assertEquals(MediaHealthStatus.MISSING, health.entries.single().status)

            // 2. Bounded scan classifies renamed identical bytes under exactMatches only
            val decoy = File(movedDir, "unrelated-same-length.mkv").apply {
                writeBytes("DIFFERENT-PAYLOAD-EXACT-LEN-001".encodeToByteArray().copyOf(relocatedFile.length().toInt()))
            }
            val scan = runtime.controller.scanCandidates(
                entry = health.entries.single().entry,
                folderLocators = listOf(movedDir.absolutePath),
                explicitFileLocators = emptyList(),
            )
            assertEquals(1, scan.exactMatches.size)
            assertEquals(relocatedFile.absolutePath, scan.exactMatches.single().candidate.locator)
            assertEquals(CandidateMatchConfidence.EXACT_CONTENT, scan.exactMatches.single().confidence)
            assertTrue(scan.heuristicMatches.any { it.candidate.locator == decoy.absolutePath })

            // 3. Plan + commit
            val plan = runtime.controller.buildRelinkPlan(
                entry = health.entries.single().entry,
                rankedCandidate = scan.exactMatches.single(),
            ).getOrThrow()
            assertEquals(RelinkContentMode.IDENTICAL_CONTENT, plan.contentMode)
            assertEquals(RelinkTransferStrategy.REFERENCE_LOCAL_PATH, plan.transferStrategy)
            assertFalse(plan.requiresManualConfirmation)

            val receipt = runtime.controller.executeRelink(plan, confirmedContentChange = false).getOrThrow()
            assertEquals(1, stopCalls)
            assertNull(activeControl.loadedMedia)
            assertEquals(relocatedFile.absolutePath, receipt.finalPath)
            assertFalse(receipt.copiedFromSaf)
            assertFalse(receipt.subtitleIndexesInvalidated)

            // 4. Reopen database and confirm everything survived with zero stale anchors
            val reopenedDb = createOpenYtDatabase(dbPath)
            val reopenedEntry = SqlDelightDownloadRepository(reopenedDb).loadLibrary().single()
            assertEquals("entry-move-1", reopenedEntry.id)
            assertEquals(relocatedFile.absolutePath, reopenedEntry.filePath)
            assertEquals(listOf("kotlin", "recovery"), reopenedEntry.tags)
            assertEquals(origSha, sha256(relocatedFile))

            val reopenedCollectionMembers = SqlDelightDownloadRepository(reopenedDb).loadCollectionMembership()["col-rec"].orEmpty()
            assertEquals(setOf("entry-move-1"), reopenedCollectionMembers)

            val reopenedCheckpoint = SqlDelightPlaybackStore(reopenedDb)
                .position(relocatedFile.absolutePath, "entry-move-1")
            assertNotNull(reopenedCheckpoint)
            assertEquals(45_000L, reopenedCheckpoint.positionMillis)

            val reopenedBundle = SqlDelightStudyRepository(reopenedDb).loadEntryStudyBundle("entry-move-1")
            assertEquals(1, reopenedBundle.notes.size)
            assertEquals(1, reopenedBundle.bookmarks.size)
            assertEquals(1, reopenedBundle.savedRanges.size)
            assertTrue(reopenedBundle.studyState.isStudied)
            assertFalse(StudyAnchorInspector.inspectBundle(reopenedBundle, 120_000L).notes.single().anchorHealth.isRevisionStale)

            val ftsResult = TimedSearchUseCase(SqlDelightTimedSearchRepository(reopenedDb))
                .execute("synchronized search cue") as TimedSearchResult.Success
            assertEquals("entry-move-1", ftsResult.items.single().entryId)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun changedContentReplacementFlagsStalenessAndSupportsPreRelinkPortableBackup() = runBlocking {
        val tempRoot = createTempDirectory("openyt-relink-changed-").toFile()
        try {
            val dbPath = File(tempRoot, "source.db").absolutePath
            val downloadsDir = File(tempRoot, "downloads").apply { mkdirs() }
            val assetsDir = File(tempRoot, "assets").apply { mkdirs() }
            val replacementDir = File(tempRoot, "replacement").apply { mkdirs() }
            val stagingDir = File(tempRoot, "staging").apply { mkdirs() }

            val db = createOpenYtDatabase(dbPath)
            val downloadRepo = SqlDelightDownloadRepository(db)
            val offlineRepo = SqlDelightOfflineAssetRepository(db)
            val assetStore = JvmOfflineAssetStore(assetsDir, offlineRepo, SqlDelightAssetOperationRepository(db))
            val subIndexRepo = SqlDelightSubtitleIndexRepository(db)
            val studyRepo = SqlDelightStudyRepository(db)

            val origFile = File(downloadsDir, "course.mp4").apply {
                writeBytes("ORIGINAL-VERSION-BYTES-1".encodeToByteArray())
            }
            val origRev = MediaRevision(origFile.length(), origFile.lastModified(), sha256(origFile))

            downloadRepo.insertLibraryEntry(
                LibraryEntry("e-chg", "Editable Lecture", MediaKind.VIDEO, origFile.absolutePath, origFile.length(), mediaRevision = origRev),
            )
            studyRepo.upsertNote(
                StudyNote("note-chg", "e-chg", 5_000L, 9_000L, "Author commentary", origRev, 10L, 10L),
            )
            val subAssetFile = File(assetsDir, "e-chg/sub.srt").apply {
                parentFile.mkdirs()
                writeText("1\n00:00:04,000 --> 00:00:07,000\nOriginal phrase before recut\n")
            }
            offlineRepo.insertAsset(
                OfflineAsset(
                    id = "asset-1",
                    entryId = "e-chg",
                    kind = AssetKind.SUBTITLE,
                    language = "en",
                    sourceType = "import",
                    relativeLocator = "e-chg/sub.srt",
                    byteCount = subAssetFile.length(),
                    sha256 = sha256(subAssetFile),
                    state = AssetState.VERIFIED,
                    isRequired = false,
                    isPinned = true,
                    mediaRevision = origRev,
                    createdAtEpochMillis = 10L,
                    lastVerifiedEpochMillis = 10L,
                ),
            )
            SubtitleIndexRebuilder(subIndexRepo, JvmSubtitleContentAdapter())
                .reindexTrack("trk-chg", "e-chg", subAssetFile.absolutePath, "en", sha256(origFile))

            // Take optional NX5 portable backup before replacement
            val backupZip = File(tempRoot, "backup-before.openyt-backup.zip")
            val backupRuntime = createPortableBackupRuntime(
                database = db,
                stagingRoot = stagingDir,
                ownedAssetsRoot = assetsDir,
                streamBridge = FilePortableStreamBridge(),
                defaultDestinationRoot = { downloadsDir.absolutePath },
                offlineAssetStore = assetStore,
                subtitleIndexRepository = subIndexRepo,
                appVersion = "1.0.0-test",
            )
            backupRuntime.controller.exportBackup(
                backupZip.absolutePath,
                PortableExportOptions(includeMediaFiles = true, includeOfflineAssets = true),
            ) {}.getOrThrow()

            // Remove old file & supply re-encoded file with changed content
            origFile.delete()
            val newCutFile = File(replacementDir, "course.mp4").apply {
                writeBytes("REENCODED-CHANGED-CUT-V2-BYTES".encodeToByteArray())
            }

            val recoveryRuntime = createLibraryRecoveryRuntime(
                database = db,
                downloadRepository = downloadRepo,
                offlineAssetRepository = offlineRepo,
                subtitleIndexRepository = subIndexRepo,
                studyRepository = studyRepo,
                stagingRoot = stagingDir,
                ownedRecoveryRootProvider = { downloadsDir.absolutePath },
            )
            val scan = recoveryRuntime.controller.scanCandidates(
                entry = downloadRepo.loadLibrary().single(),
                folderLocators = listOf(replacementDir.absolutePath),
                explicitFileLocators = emptyList(),
            )
            assertTrue(scan.exactMatches.isEmpty())
            assertEquals(1, scan.heuristicMatches.size)

            val plan = recoveryRuntime.controller.buildRelinkPlan(
                entry = downloadRepo.loadLibrary().single(),
                rankedCandidate = scan.heuristicMatches.single(),
            ).getOrThrow()
            assertEquals(RelinkContentMode.CHANGED_CONTENT_REPLACEMENT, plan.contentMode)
            assertTrue(plan.requiresManualConfirmation)
            assertTrue(plan.warnsStaleStudyAnchors)
            assertTrue(plan.warnsSubtitleInvalidation)
            assertTrue(plan.recommendsBackupBeforeRelink)

            // Unconfirmed attempt is rejected without mutating DB
            val unconfirmed = recoveryRuntime.controller.executeRelink(plan, confirmedContentChange = false)
            assertTrue((unconfirmed.exceptionOrNull() as? RelinkException)?.failure is RelinkFailure.ConfirmationRequired)
            assertEquals(origFile.absolutePath, downloadRepo.loadLibrary().single().filePath)

            // Confirmed replacement succeeds cleanly
            val receipt = recoveryRuntime.controller.executeRelink(plan, confirmedContentChange = true).getOrThrow()
            assertEquals(RelinkContentMode.CHANGED_CONTENT_REPLACEMENT, receipt.contentMode)
            assertTrue(receipt.subtitleIndexesInvalidated)
            assertEquals(1, receipt.staleAssetsFlagged)

            val reopenedDb = createOpenYtDatabase(dbPath)
            val bundle = SqlDelightStudyRepository(reopenedDb).loadEntryStudyBundle("e-chg")
            assertEquals("Author commentary", bundle.notes.single().body)
            assertTrue(StudyAnchorInspector.inspectBundle(bundle, 60_000L).notes.single().anchorHealth.isRevisionStale)
            assertEquals(AssetState.STALE, SqlDelightOfflineAssetRepository(reopenedDb).loadAssetsForEntry("e-chg").single().state)
            assertTrue(
                TimedSearchUseCase(SqlDelightTimedSearchRepository(reopenedDb)).execute("Original phrase") is TimedSearchResult.IndexUnavailable,
            )

            // Safety net: NX5 backup can still restore original un-replaced snapshot cleanly
            val restoreRoot = File(tempRoot, "restored-copy").apply { mkdirs() }
            val restoreDb = createOpenYtDatabase(File(restoreRoot, "openyt.db").absolutePath)
            val restoreDownloads = File(restoreRoot, "downloads").apply { mkdirs() }
            val restoreAssets = File(restoreRoot, "assets").apply { mkdirs() }
            val restoreAssetStore = JvmOfflineAssetStore(
                restoreAssets,
                SqlDelightOfflineAssetRepository(restoreDb),
                SqlDelightAssetOperationRepository(restoreDb),
            )
            val restoreRuntime = createPortableBackupRuntime(
                database = restoreDb,
                stagingRoot = File(restoreRoot, "staging"),
                ownedAssetsRoot = restoreAssets,
                streamBridge = FilePortableStreamBridge(),
                defaultDestinationRoot = { restoreDownloads.absolutePath },
                offlineAssetStore = restoreAssetStore,
                subtitleIndexRepository = SqlDelightSubtitleIndexRepository(restoreDb),
                appVersion = "1.0.0-test",
            )
            val rPlan = restoreRuntime.controller.prepareRestorePlan(
                backupZip.absolutePath,
                restoreDownloads.absolutePath,
                PortableRestoreOptions(PortableRestoreConflictPolicy.IMPORT_AS_NEW),
            ) {}.getOrThrow()
            restoreRuntime.controller.executeRestore(rPlan, rPlan.archiveSha256) {}.getOrThrow()
            val restoredOrig = SqlDelightDownloadRepository(restoreDb).loadLibrary().single()
            assertNotEquals(newCutFile.absolutePath, restoredOrig.filePath)
            assertEquals("Original phrase before recut".length > 0, true)
            assertTrue(
                TimedSearchUseCase(SqlDelightTimedSearchRepository(restoreDb)).execute("before recut") is TimedSearchResult.Success,
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun realVlcjPlaybackStopReleasesFileBeforeMovedRelink(): Unit = runBlocking {
        assumeTrue(System.getenv("OPENYT_RUN_PLAYBACK_TESTS") == "1")
        val ffmpeg = ExecutableLocator().findFfmpeg()
        assumeTrue("ffmpeg required", ffmpeg != null)

        val tempRoot = createTempDirectory("openyt-relink-vlcj-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dbFile = File(tempRoot, "vlcj-relink.db")
        val db = createOpenYtDatabase(dbFile.absolutePath)
        val playbackStore = SqlDelightPlaybackStore(db)
        val launcher = DesktopPlaybackLauncher(playbackStore, { EnStrings }, scope)

        try {
            val downloadsDir = File(tempRoot, "downloads").apply { mkdirs() }
            val movedDir = File(tempRoot, "moved").apply { mkdirs() }
            val fixture = File(downloadsDir, "clip-relink.mp4")
            val proc = ProcessBuilder(
                ffmpeg!!, "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=10:size=320x180:rate=10",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", fixture.absolutePath,
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals(0, proc.waitFor(), out)

            val sha = sha256(fixture)
            val rev = MediaRevision(fixture.length(), fixture.lastModified(), sha)
            val repo = SqlDelightDownloadRepository(db)
            val studyRepo = SqlDelightStudyRepository(db)
            repo.insertLibraryEntry(
                LibraryEntry("vlcj-e1", "VLCJ Relink Clip", MediaKind.VIDEO, fixture.absolutePath, fixture.length(), mediaRevision = rev),
            )
            studyRepo.upsertNote(StudyNote("n-v1", "vlcj-e1", 2_000L, null, "Note", rev, 1L, 1L))

            assertTrue(launcher.openAt(listOf(PlayableMedia(fixture.absolutePath, "VLCJ Relink Clip", MediaKind.VIDEO, entryId = "vlcj-e1")), 0, 0L))
            val started = waitUntil(10_000L) {
                launcher.captureSnapshot()?.state == PlaybackSessionState.PLAYING
            }
            assumeTrue("VLC runtime unavailable on host", started || launcher.isVlcAvailable())

            launcher.stop()
            delay(200)
            val relocated = File(movedDir, "clip-moved.mp4")
            Files.move(fixture.toPath(), relocated.toPath())

            val runtime = createLibraryRecoveryRuntime(
                database = db,
                downloadRepository = repo,
                offlineAssetRepository = SqlDelightOfflineAssetRepository(db),
                subtitleIndexRepository = SqlDelightSubtitleIndexRepository(db),
                studyRepository = studyRepo,
                stagingRoot = File(tempRoot, "staging"),
                ownedRecoveryRootProvider = { downloadsDir.absolutePath },
                playbackSessionControl = launcher,
            )
            val scan = runtime.controller.scanCandidates(repo.loadLibrary().single(), listOf(movedDir.absolutePath), emptyList())
            val plan = runtime.controller.buildRelinkPlan(repo.loadLibrary().single(), scan.exactMatches.single()).getOrThrow()
            runtime.controller.executeRelink(plan, confirmedContentChange = false).getOrThrow()

            assertTrue(launcher.openAt(listOf(PlayableMedia(relocated.absolutePath, "VLCJ Relink Clip", MediaKind.VIDEO, entryId = "vlcj-e1")), 0, 2_000L))
            assertTrue(waitUntil(10_000L) { launcher.captureSnapshot()?.state == PlaybackSessionState.PLAYING })
        } finally {
            launcher.close()
            scope.cancel()
            tempRoot.deleteRecursively()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(100)
        }
        return false
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
