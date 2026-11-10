package com.openyt.core.application

import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.PlaylistEntry
import com.openyt.core.domain.PlaylistEntrySelection
import com.openyt.core.domain.PlaylistInfo
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class EnqueuePlaylistUseCaseTest {
    @Test
    fun organizesEntriesIntoNumberedFolder() = runTest {
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope())
        val playlist = playlist(
            title = "My playlist",
            entries = listOf(entry(1, "First"), entry(2, "Second")),
        )

        val ids = EnqueuePlaylistUseCase(coordinator)(
            playlist,
            playlist.entries,
            MediaKind.VIDEO,
            "/tmp/openyt",
        )
        advanceUntilIdle()

        assertEquals(2, ids.size)
        val tasks = coordinator.tasks.value
        assertEquals("/tmp/openyt/My playlist", tasks[0].request.destinationDirectory)
        assertEquals("01 - %(title).150B [%(id)s].%(ext)s", tasks[0].request.fileNameTemplate)
        assertEquals("02 - %(title).150B [%(id)s].%(ext)s", tasks[1].request.fileNameTemplate)
    }

    @Test
    fun sanitizesPlaylistFolderName() = runTest {
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope())
        val playlist = playlist(
            title = "../../AC/DC: Live?",
            entries = listOf(entry(1, "First")),
        )

        EnqueuePlaylistUseCase(coordinator)(
            playlist,
            playlist.entries,
            MediaKind.AUDIO,
            "/tmp/openyt/",
        )
        advanceUntilIdle()

        val directory = coordinator.tasks.value.single().request.destinationDirectory
        assertEquals(".._.._AC_DC_ Live_", directory.substringAfterLast('/'))
        assertFalse(directory.split('/').any { it == ".." })
    }

    @Test
    fun appliesPerEntryFormatOverrides() = runTest {
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope())
        val playlist = playlist(
            title = "Overrides",
            entries = listOf(entry(1, "First"), entry(2, "Second")),
        )

        EnqueuePlaylistUseCase(coordinator)(
            playlist,
            playlist.entries,
            MediaKind.VIDEO,
            "/tmp/openyt",
            overrides = mapOf(
                2 to PlaylistEntrySelection(MediaKind.AUDIO, "audio-format"),
            ),
        )
        advanceUntilIdle()

        val tasks = coordinator.tasks.value
        assertEquals(MediaKind.VIDEO, tasks[0].request.kind)
        assertNull(tasks[0].request.formatId)
        assertEquals(MediaKind.AUDIO, tasks[1].request.kind)
        assertEquals("audio-format", tasks[1].request.formatId)
    }

    private fun TestScope.coordinatorScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private fun playlist(title: String, entries: List<PlaylistEntry>) = PlaylistInfo(
        sourceId = "list",
        source = "youtube",
        title = title,
        webpageUrl = "https://example.com/list",
        entries = entries,
    )

    private fun entry(index: Int, title: String) = PlaylistEntry(
        index = index,
        title = title,
        url = "https://example.com/watch?v=$index",
    )
}
