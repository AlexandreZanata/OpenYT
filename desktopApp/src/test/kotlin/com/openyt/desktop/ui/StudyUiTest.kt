package com.openyt.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openyt.core.application.*
import com.openyt.core.domain.*
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.LibraryScreen
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class StudyUiTest(private val scenario: Scenario) {

    @Test
    fun studyWorkflowNotesAbRepeatExportAndRemovalPreview() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        val tempFile = createTempFile("openyt-study-ui-", ".mp4").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val studyRepo = MemoryStudyRepository()
        val downloadRepo = InMemoryStudyDownloadRepo()
        val entry1 = LibraryEntry(
            id = "e-study-1",
            title = "Clean Kotlin Study Lesson",
            kind = MediaKind.VIDEO,
            filePath = tempFile.absolutePath,
            sizeBytes = tempFile.length(),
            mediaRevision = MediaRevision(tempFile.length(), 100L, "sha-v1"),
        )
        val entry2 = LibraryEntry(
            id = "e-study-2",
            title = "Second Architecture Lesson",
            kind = MediaKind.AUDIO,
            filePath = tempFile.absolutePath,
            sizeBytes = tempFile.length(),
            mediaRevision = MediaRevision(tempFile.length(), 100L, "sha-v1"),
        )

        var clock = 10_000L
        var seq = 0
        runBlocking {
            downloadRepo.insertLibraryEntry(entry1)
            downloadRepo.insertLibraryEntry(entry2)
            studyRepo.putLibraryEntry(entry1)
            studyRepo.putLibraryEntry(entry2)
            studyRepo.updateEntryRevision(entry1.id, MediaRevision(tempFile.length(), 200L, "sha-v2"), 200L)
        }
        val studyService = StudyService(studyRepo, nowEpochMillis = { ++clock }, nextId = { "ui-study-${++seq}" })
        runBlocking {
            // Seed one stale note anchored to old sha-v1 so stale anchor diagnostics + re-anchor are exercised
            studyRepo.upsertNote(
                StudyNote(
                    id = "stale-note-1",
                    entryId = entry1.id,
                    timestampMillis = 15_000L,
                    body = "Stale anchor note body",
                    mediaRevision = MediaRevision(tempFile.length(), 100L, "sha-v1"),
                    createdAtEpochMillis = 500L,
                    updatedAtEpochMillis = 500L,
                ),
            )
        }

        val fakeSession = FakeUiPlaybackSession(
            entry1 = entry1,
            durationMillis = 90_000L,
            initialPos = 22_000L,
        )
        val captureService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = fakeSession,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1, entry2) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { ++clock },
        )
        val editorCoordinator = StudyEditorCoordinator()
        val abController = StudyAbRepeatController(fakeSession, scope)

        var exportOutcome: TextExportOutcome = TextExportOutcome.Canceled
        val fakeExporter = object : TextFileExporter {
            override val canExport: Boolean = true
            var lastMarkdown: String = ""
            override suspend fun exportMarkdown(suggestedFilename: String, content: String): TextExportOutcome {
                lastMarkdown = content
                return exportOutcome
            }
        }

        val coordinator = DownloadCoordinator(SuspendedDownloadEngine(), scope, repository = downloadRepo)
        coordinator.restoreSyncForTest()
        val libraryController = LibraryController(downloadRepo, scope)
        runBlocking {
            coordinator.restore()
            libraryController.restore()
        }

        try {
            setContent {
                OpenYtTheme(darkTheme = scenario.dark) {
                    CompositionLocalProvider(LocalStrings provides scenario.strings) {
                        Box(Modifier.width(scenario.size.width.dp)) {
                            LibraryScreen(
                                coordinator = coordinator,
                                fileAvailability = FileAvailability { true },
                                libraryController = libraryController,
                                mediaProbe = MediaProbe { MediaTechnicalInfo(durationMillis = 90_000L) },
                                mediaActions = NoopMediaActions,
                                mediaImporter = null,
                                downloadsDirectory = tempFile.parent,
                                studyService = studyService,
                                studyCaptureService = captureService,
                                studyEditorCoordinator = editorCoordinator,
                                studyAbRepeatController = abController,
                                studyExporter = fakeExporter,
                                hostLinkCapability = StudyHostLinkCapability(supportsClickableEntryLinks = true, hostLabel = "test"),
                            )
                        }
                    }
                }
            }

            // Progress starts at 0 / 2
            waitUntilAtLeastOneExists(hasText(scenario.strings.study.studyProgressLabel(0, 2)), timeoutMillis = 3000)

            // Scroll grid to entry 1 and open Study dialog
            onNodeWithTag("library-grid").performScrollToKey(entry1.id)
            waitUntilAtLeastOneExists(hasTestTag("study-entry-${entry1.id}"), timeoutMillis = 3000)
            onNodeWithTag("study-entry-${entry1.id}").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag("study-sheet-title"), timeoutMillis = 3000)

            // Verify stale anchor diagnostic & re-anchor action work
            waitUntilAtLeastOneExists(hasText(scenario.strings.study.staleAnchorBadge), timeoutMillis = 3000)
            onNodeWithText(scenario.strings.study.reanchorAction).performScrollTo().performClick()
            waitForIdle()
            assertEquals(0, onAllNodesWithText(scenario.strings.study.staleAnchorBadge).fetchSemanticsNodes().size)

            // Create new note -> test validation retaining text -> test dirty close prompt -> save cleanly
            onNodeWithTag("study-new-note-button").performScrollTo().performClick()
            onNodeWithTag("study-draft-save").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText(scenario.strings.study.emptyNoteBodyError), timeoutMillis = 3000)

            onNodeWithTag("study-draft-text-input").performScrollTo().performTextInput("Key takeaway line")
            // Attempt to close sheet while draft is dirty -> must prompt discard
            onNodeWithTag("study-sheet-close").performClick()
            waitUntilAtLeastOneExists(hasTestTag("study-discard-keep"), timeoutMillis = 3000)
            onNodeWithTag("study-discard-keep").performClick()
            waitForIdle()
            // Save valid note cleanly
            onNodeWithTag("study-draft-save").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText("Key takeaway line"), timeoutMillis = 3000)

            // Test A/B repeat Set A -> advance playback -> Set B -> Save range -> Start/Stop loop
            onNodeWithTag("study-ab-set-a").performScrollTo().performClick()
            fakeSession.setPosition(28_000L)
            onNodeWithTag("study-ab-set-b").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag("study-ab-save-range"), timeoutMillis = 3000)
            onNodeWithTag("study-ab-save-range").performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, runBlocking { studyService.loadEntryStudyBundle(entry1.id).savedRanges.size })

            // Toggle manual studied completion
            onNodeWithTag("study-studied-checkbox").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText(scenario.strings.study.studiedCompletedLabel), timeoutMillis = 3000)

            // Test export canceled, failed, and succeeded
            exportOutcome = TextExportOutcome.Canceled
            onNodeWithTag("study-export-button").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText(scenario.strings.study.exportCanceled), timeoutMillis = 3000)

            exportOutcome = TextExportOutcome.Failed("disk full")
            onNodeWithTag("study-export-button").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText(scenario.strings.study.exportFailed("disk full")), timeoutMillis = 3000)

            exportOutcome = TextExportOutcome.Exported("notes.md")
            onNodeWithTag("study-export-button").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText(scenario.strings.study.exportSuccess("notes.md")), timeoutMillis = 3000)
            assertTrue("openyt://library/entry/${entry1.id}?t=" in fakeExporter.lastMarkdown)

            // Close sheet and verify header progress 1 / 2 and card studied badge
            onNodeWithTag("study-sheet-close").performClick()
            onNodeWithTag("library-grid").performScrollToIndex(0)
            waitUntilAtLeastOneExists(hasText(scenario.strings.study.studyProgressLabel(1, 2)), timeoutMillis = 3000)
            onNodeWithTag("library-grid").performScrollToKey(entry1.id)
            waitUntilAtLeastOneExists(hasTestTag("card-studied-${entry1.id}"), timeoutMillis = 3000)
            onNodeWithTag("card-studied-${entry1.id}").performScrollTo().assertIsDisplayed()

            // Remove entry 1 with authored notes -> Removal Preview Confirmation dialog appears & preserves media
            onNodeWithTag("remove-entry-${entry1.id}").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag("study-removal-title"), timeoutMillis = 3000)
            onNodeWithTag("study-removal-cancel").performClick()
            waitForIdle()
            assertTrue(coordinator.library.value.any { it.id == entry1.id })

            onNodeWithTag("remove-entry-${entry1.id}").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag("study-removal-confirm"), timeoutMillis = 3000)
            onNodeWithTag("study-removal-confirm").performClick()
            waitForIdle()
            assertFalse(coordinator.library.value.any { it.id == entry1.id })
            assertTrue(tempFile.exists(), "Original media file on disk must NEVER be deleted")
        } finally {
            abController.close()
            scope.cancel()
            tempFile.delete()
        }
    }

    @Test
    fun localEntryDeepLinkOpensPreviewWithoutAutoPlaying() = runSkikoComposeUiTest(
        size = Size(1024f, 768f),
        density = Density(1f, fontScale = 1f),
    ) {
        val tempFile = createTempFile("openyt-link-ui-", ".mp4").toFile().apply { writeBytes(byteArrayOf(1, 2)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val studyRepo = MemoryStudyRepository()
        val downloadRepo = InMemoryStudyDownloadRepo()
        val entry = LibraryEntry(
            id = "deep-entry-1",
            title = "Deep Linked Lecture",
            kind = MediaKind.VIDEO,
            filePath = tempFile.absolutePath,
            sizeBytes = tempFile.length(),
        )
        runBlocking {
            downloadRepo.insertLibraryEntry(entry)
            studyRepo.putLibraryEntry(entry)
        }
        val studyService = StudyService(studyRepo)
        val fakeSession = FakeUiPlaybackSession(entry1 = entry, durationMillis = 120_000L, initialPos = 0L, startsIdle = true)
        val captureService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = fakeSession,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry) },
            fileAvailability = FileAvailability { true },
        )
        val coordinator = DownloadCoordinator(SuspendedDownloadEngine(), scope, repository = downloadRepo)
        runBlocking { coordinator.restore() }

        try {
            setContent {
                OpenYtTheme {
                    CompositionLocalProvider(LocalStrings provides scenario.strings) {
                        LibraryScreen(
                            coordinator = coordinator,
                            fileAvailability = FileAvailability { true },
                            libraryController = LibraryController(downloadRepo, scope),
                            mediaProbe = MediaProbe { null },
                            mediaActions = NoopMediaActions,
                            mediaImporter = null,
                            downloadsDirectory = tempFile.parent,
                            studyService = studyService,
                            studyCaptureService = captureService,
                            pendingEntryRoute = OpenYtRoute.LibraryEntry(entry.id, 42_000L),
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag("study-deeplink-preview"), timeoutMillis = 3000)
            // Must NOT auto-play upon deep-link preview opening!
            assertEquals(0, fakeSession.openAtCalls)
            onNodeWithTag("study-deeplink-play").performClick()
            waitForIdle()
            assertEquals(1, fakeSession.openAtCalls)
            assertEquals(42_000L, fakeSession.currentPositionMillis)
        } finally {
            scope.cancel()
            tempFile.delete()
        }
    }

    data class Scenario(
        val name: String,
        val size: Size,
        val fontScale: Float,
        val dark: Boolean,
        val strings: AppStrings,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = listOf(
            arrayOf(Scenario("mobile-320-dark-pt", Size(320f, 640f), 1.5f, true, PtBrStrings)),
            arrayOf(Scenario("desktop-en-large-200", Size(1280f, 800f), 2.0f, false, EnStrings)),
        )
    }
}

private fun DownloadCoordinator.restoreSyncForTest() = Unit

private class InMemoryStudyDownloadRepo : DownloadRepository {
    private val library = mutableListOf<LibraryEntry>()
    override suspend fun loadTasks(): List<DownloadTask> = emptyList()
    override suspend fun insertTask(task: DownloadTask) = Unit
    override suspend fun updateTask(task: DownloadTask) = Unit
    override suspend fun deleteTask(id: String) = Unit
    override suspend fun markActiveInterrupted() = Unit
    override suspend fun loadLibrary(): List<LibraryEntry> = library.toList()
    override suspend fun insertLibraryEntry(entry: LibraryEntry) { library.add(entry) }
    override suspend fun addLibraryTag(entryId: String, tag: String) = Unit
    override suspend fun removeLibraryTag(entryId: String, tag: String) = Unit
    override suspend fun updateLibraryMetadata(id: String, title: String, author: String?) = Unit
    override suspend fun updateLibraryPath(id: String, filePath: String) = Unit
    override suspend fun deleteLibraryEntry(id: String) { library.removeAll { it.id == id } }
    override suspend fun loadCollections(): List<com.openyt.core.domain.Collection> = emptyList()
    override suspend fun insertCollection(collection: com.openyt.core.domain.Collection) = Unit
    override suspend fun deleteCollection(id: String) = Unit
    override suspend fun loadCollectionMembership(): Map<String, Set<String>> = emptyMap()
    override suspend fun addEntryToCollection(collectionId: String, entryId: String) = Unit
    override suspend fun removeEntryFromCollection(collectionId: String, entryId: String) = Unit
    override suspend fun loadSmartCollections(): List<SmartCollection> = emptyList()
    override suspend fun insertSmartCollection(collection: SmartCollection) = Unit
    override suspend fun deleteSmartCollection(id: String) = Unit
}

private class FakeUiPlaybackSession(
    entry1: LibraryEntry,
    override var durationMillis: Long,
    initialPos: Long,
    startsIdle: Boolean = false,
) : PlaybackLauncher, PlaybackSessionControl {
    override val canPlay: Boolean = true
    override val capabilities: PlaybackCapabilities = PlaybackCapabilities()
    override var loadedMedia: PlayableMedia? = if (startsIdle) {
        null
    } else {
        PlayableMedia(entry1.filePath, entry1.title, entry1.kind, entry1.id)
    }
    override var state: PlaybackSessionState = if (startsIdle) PlaybackSessionState.IDLE else PlaybackSessionState.PLAYING
    override var currentPositionMillis: Long = initialPos
    override val currentSessionGeneration: Long = 1L
    var openAtCalls: Int = 0
    private val listeners = mutableListOf<PlaybackSessionListener>()

    private fun notifyListeners() {
        val snap = captureSnapshot()
        listeners.toList().forEach { it.onSessionSnapshot(snap) }
    }

    fun setPosition(pos: Long) {
        currentPositionMillis = pos
        notifyListeners()
    }

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        loadedMedia = items.getOrNull(startIndex)
        state = PlaybackSessionState.PLAYING
        openAtCalls++
        notifyListeners()
    }

    override fun playAt(items: List<PlayableMedia>, startIndex: Int, startPositionMillis: Long) {
        loadedMedia = items.getOrNull(startIndex)
        currentPositionMillis = startPositionMillis
        state = PlaybackSessionState.PLAYING
        openAtCalls++
        notifyListeners()
    }

    override suspend fun openAt(
        items: List<PlayableMedia>,
        startIndex: Int,
        startPositionMillis: Long?,
    ): Boolean {
        loadedMedia = items.getOrNull(startIndex)
        currentPositionMillis = startPositionMillis ?: 0L
        state = PlaybackSessionState.PLAYING
        openAtCalls++
        notifyListeners()
        return true
    }

    override suspend fun seekTo(positionMillis: Long): Boolean {
        currentPositionMillis = positionMillis
        notifyListeners()
        return true
    }

    override suspend fun pause() {
        state = PlaybackSessionState.PAUSED
        notifyListeners()
    }

    override suspend fun play() {
        state = PlaybackSessionState.PLAYING
        notifyListeners()
    }

    override suspend fun stop() {
        state = PlaybackSessionState.IDLE
        notifyListeners()
    }

    override fun close() {
        state = PlaybackSessionState.IDLE
        loadedMedia = null
        notifyListeners()
    }

    override fun registerSessionListener(listener: PlaybackSessionListener): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    override suspend fun resumeLastSession(): Boolean = false
}
