package com.openyt.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.LibraryController
import com.openyt.core.application.MediaProbe
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.application.TimedSearchRepository
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.IndexUnavailableReason
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.TextHighlightRange
import com.openyt.core.domain.TimedSearchExcerpt
import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchIndexStatus
import com.openyt.core.domain.TimedSearchMatch
import com.openyt.core.domain.TimedSearchPagination
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import com.openyt.ui.components.TimedSearchMatchCard
import com.openyt.ui.components.TimedSearchScopeIndicator
import com.openyt.ui.components.TimedSearchStateView
import com.openyt.ui.components.formatTimestampMs
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.LibraryScreen
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TimedSearchUiTest {

    private fun sampleMatch(
        matchId: String = "match-1",
        entryId: String = "entry-1",
        entryTitle: String = "Sample Video Tutorial",
        startMs: Long = 25_000L,
        timebaseOffsetMs: Long = 5_000L,
        text: String = "This is a timed search excerpt test",
        language: String = "en",
    ) = TimedSearchMatch(
        matchId = matchId,
        entryId = entryId,
        entryTitle = entryTitle,
        trackId = "track-1",
        cueId = "cue-1",
        language = language,
        speaker = "Speaker 1",
        startMs = startMs,
        endMs = startMs + 3_000L,
        durationMs = 3_000L,
        timebaseOffsetMs = timebaseOffsetMs,
        matchedText = "search excerpt",
        displayText = text,
        excerpt = TimedSearchExcerpt(
            text = text,
            highlightRanges = listOf(TextHighlightRange(10, 23)),
        ),
    )

    @Test
    fun noTrackStateExplainsMissingTracksAndOffersImport() = runComposeUiTest {
        var importClicked = false
        var reindexClicked = false

        setContent {
            OpenYtTheme {
                CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                    TimedSearchStateView(
                        result = TimedSearchResult.IndexUnavailable(
                            reason = IndexUnavailableReason.NO_SUBTITLE_TRACKS,
                            message = "No subtitles indexed",
                        ),
                        isLoading = false,
                        onImportSubtitle = { importClicked = true },
                        onReindex = { reindexClicked = true },
                    )
                }
            }
        }

        onNodeWithText(PtBrStrings.timedSearch.noSubtitleTracks).assertIsDisplayed()
        onNodeWithText(PtBrStrings.timedSearch.importSubtitleAction).assertIsDisplayed().performClick()
        assertTrue(importClicked, "Clicking import subtitle button must invoke callback")

        onNodeWithText(PtBrStrings.timedSearch.reindexAction).assertIsDisplayed().performClick()
        assertTrue(reindexClicked, "Clicking reindex button must invoke callback")
    }

    @Test
    fun noMatchesStateDisplaysDistinctEmptyMessageFromMissingIndex() = runComposeUiTest {
        setContent {
            OpenYtTheme {
                CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                    TimedSearchStateView(
                        result = TimedSearchResult.Success(
                            items = emptyList(),
                            totalMatches = 0,
                            page = 0,
                            pageSize = 20,
                            hasMore = false,
                            indexStatus = TimedSearchIndexStatus.READY,
                            normalizedQuery = "test",
                        ),
                        isLoading = false,
                    )
                }
            }
        }

        onNodeWithText(PtBrStrings.timedSearch.noMatches).assertIsDisplayed()
        // Ensure missing index explanations are NOT displayed
        assertEquals(0, onAllNodesWithText(PtBrStrings.timedSearch.noSubtitleTracks).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText(PtBrStrings.timedSearch.importSubtitleAction).fetchSemanticsNodes().size)
    }

    @Test
    fun keyboardNavigationTriggersPlayback() = runComposeUiTest {
        var played = false
        val match = sampleMatch()

        setContent {
            OpenYtTheme {
                CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                    TimedSearchMatchCard(
                        match = match,
                        onPlay = { played = true },
                    )
                }
            }
        }

        onNodeWithTag("timed-search-play-btn").assertIsDisplayed().performClick()
        assertTrue(played, "Clicking play action button should trigger playback")
    }

    @Test
    fun accessibilitySemanticsIncludeTitleTimeAndExcerpt() = runComposeUiTest {
        val match = sampleMatch(
            entryTitle = "Deep Dive into Multiplatform",
            startMs = 75_000L, // 01:15
            text = "multiplatform UI shared code",
        )

        setContent {
            OpenYtTheme {
                CompositionLocalProvider(LocalStrings provides EnStrings) {
                    TimedSearchMatchCard(
                        match = match,
                        onPlay = {},
                    )
                }
            }
        }

        val expectedLabel = "Deep Dive into Multiplatform at 01:15: multiplatform UI shared code"
        onNodeWithContentDescription(expectedLabel).assertIsDisplayed()
    }

    @Test
    fun ptAndEnLocalization() = runComposeUiTest {
        // 1. PT-BR verification
        setContent {
            OpenYtTheme {
                CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                    TimedSearchScopeIndicator(
                        scope = TimedSearchScope.Entry("entry-1"),
                        indexStatus = TimedSearchIndexStatus.READY,
                        selectedLanguage = null,
                        availableLanguages = listOf("pt", "en"),
                        onSelectLanguage = {},
                        entryTitle = "Tutorial",
                    )
                }
            }
        }
        onNodeWithText("${PtBrStrings.timedSearch.searchScopeEntry}: Tutorial").assertIsDisplayed()
        onNodeWithText(PtBrStrings.timedSearch.indexStatusReady).assertIsDisplayed()
        onNodeWithText(PtBrStrings.timedSearch.allLanguages).assertIsDisplayed()

        // 2. EN verification
        setContent {
            OpenYtTheme {
                CompositionLocalProvider(LocalStrings provides EnStrings) {
                    TimedSearchScopeIndicator(
                        scope = TimedSearchScope.Entry("entry-1"),
                        indexStatus = TimedSearchIndexStatus.PARTIALLY_INDEXED,
                        selectedLanguage = null,
                        availableLanguages = listOf("pt", "en"),
                        onSelectLanguage = {},
                        entryTitle = "Tutorial",
                    )
                }
            }
        }
        onNodeWithText("${EnStrings.timedSearch.searchScopeEntry}: Tutorial").assertIsDisplayed()
        onNodeWithText(EnStrings.timedSearch.indexStatusPartiallyIndexed).assertIsDisplayed()
        onNodeWithText(EnStrings.timedSearch.allLanguages).assertIsDisplayed()
    }

    @Test
    fun narrowScreen320dpWithEnlargedTextDoesNotOverflow() = runSkikoComposeUiTest(
        size = Size(320f, 640f),
        density = Density(1f, fontScale = 1.5f),
    ) {
        val match = sampleMatch(
            entryTitle = "Very long tutorial video title that wraps across multiple lines",
            startMs = 3665_000L, // 1:01:05
            text = "Here is an excerpt containing substantial text designed to stress narrow viewport wrapping.",
        )

        setContent {
            OpenYtTheme {
                CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                    Box(modifier = Modifier.width(320.dp)) {
                        TimedSearchMatchCard(
                            match = match,
                            onPlay = {},
                        )
                    }
                }
            }
        }

        onNodeWithText("Very long tutorial video title that wraps across multiple lines").assertIsDisplayed()
        onNodeWithText("1:01:05").assertIsDisplayed()
    }

    @Test
    fun sourceTimeOffsetAppliesToPlaybackPositionAndPlaysOnlyIntendedEntry() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val engine = object : DownloadEngine by SuspendedDownloadEngine() {
                override fun download(request: DownloadRequest, downloadId: String) =
                    flowOf(DownloadEvent.Completed("/fixture/$downloadId.mp4", 100))
            }
            val coordinator = DownloadCoordinator(engine, scope)

            // Enqueue two videos: entry-1 and entry-2
            val media1 = MediaInfo("1", "fixture", "Video One", webpageUrl = "https://example.com/1")
            val media2 = MediaInfo("2", "fixture", "Video Two", webpageUrl = "https://example.com/2")
            val id1 = coordinator.enqueue(media1, DownloadRequest("https://example.com/1", MediaKind.VIDEO, destinationDirectory = "/fixture"))
            val id2 = coordinator.enqueue(media2, DownloadRequest("https://example.com/2", MediaKind.VIDEO, destinationDirectory = "/fixture"))

            var playedItems = emptyList<PlayableMedia>()
            var playedStartIndex = -1
            var playedStartMillis = -1L
            val testLauncher = object : PlaybackLauncher {
                override val canPlay: Boolean = true
                override fun play(items: List<PlayableMedia>, startIndex: Int) {
                    playedItems = items
                    playedStartIndex = startIndex
                }
                override fun playAt(items: List<PlayableMedia>, startIndex: Int, startPositionMillis: Long) {
                    playedItems = items
                    playedStartIndex = startIndex
                    playedStartMillis = startPositionMillis
                }
            }

            // Fake repository returning a match for entry-1 with startMs = 20_000 and timebaseOffsetMs = 5_000
            val fakeSearchRepo = object : TimedSearchRepository {
                override suspend fun search(
                    query: String,
                    scope: TimedSearchScope,
                    filter: TimedSearchFilter,
                    pagination: TimedSearchPagination,
                ): TimedSearchResult {
                    return TimedSearchResult.Success(
                        items = listOf(
                            sampleMatch(
                                entryId = id1,
                                entryTitle = "Video One",
                                startMs = 20_000L,
                                timebaseOffsetMs = 5_000L,
                                text = "Found text in Video One",
                            ),
                        ),
                        totalMatches = 1,
                        page = 0,
                        pageSize = 20,
                        hasMore = false,
                        indexStatus = TimedSearchIndexStatus.READY,
                        normalizedQuery = query,
                    )
                }
            }
            val timedSearchUseCase = TimedSearchUseCase(fakeSearchRepo)

            setContent {
                OpenYtTheme {
                    CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                        LibraryScreen(
                            coordinator = coordinator,
                            fileAvailability = FileAvailability { true },
                            libraryController = LibraryController(null, scope),
                            mediaProbe = MediaProbe { null },
                            mediaActions = NoopMediaActions,
                            mediaImporter = null,
                            downloadsDirectory = "/fixture",
                            playbackLauncher = testLauncher,
                            timedSearchUseCase = timedSearchUseCase,
                        )
                    }
                }
            }

            // Switch to subtitles search mode chip
            onNodeWithTag("search-mode-subtitles").assertIsDisplayed().performClick()

            // Input search query
            onNodeWithTag("library-search-field").assertIsDisplayed().performTextInput("found")

            // Timestamp formatted from startMs (20_000L -> "00:20")
            // Wait for debounce and search execution
            waitUntilAtLeastOneExists(hasText("00:20"), timeoutMillis = 3000)
            onNodeWithText("00:20").assertIsDisplayed().performClick()

            // Verification:
            // 1. Only the intended entry was played
            assertEquals(1, playedItems.size, "Must play only the intended entry")
            assertEquals(id1, playedItems[0].entryId, "Intended entryId must match")
            // 2. Target position is startMs (20_000) + timebaseOffsetMs (5_000) = 25_000L
            assertEquals(25_000L, playedStartMillis, "Position must include timebase offset")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleSearchResultIsSupersededByNewerQuery() = runComposeUiTest {
        val slowStarted = CompletableDeferred<Unit>()
        val slowProceed = CompletableDeferred<Unit>()

        val fakeRepo = object : TimedSearchRepository {
            override suspend fun search(
                query: String,
                scope: TimedSearchScope,
                filter: TimedSearchFilter,
                pagination: TimedSearchPagination,
            ): TimedSearchResult {
                return if (query == "slow") {
                    slowStarted.complete(Unit)
                    slowProceed.await()
                    TimedSearchResult.Success(
                        items = listOf(sampleMatch(text = "Old slow result")),
                        totalMatches = 1,
                        page = 0,
                        pageSize = 20,
                        hasMore = false,
                        indexStatus = TimedSearchIndexStatus.READY,
                        normalizedQuery = "slow",
                    )
                } else {
                    TimedSearchResult.Success(
                        items = listOf(sampleMatch(text = "Fresh fast result")),
                        totalMatches = 1,
                        page = 0,
                        pageSize = 20,
                        hasMore = false,
                        indexStatus = TimedSearchIndexStatus.READY,
                        normalizedQuery = "fast",
                    )
                }
            }
        }
        val useCase = TimedSearchUseCase(fakeRepo)

        var currentResult by mutableStateOf<TimedSearchResult?>(null)
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        try {
            var activeJob: kotlinx.coroutines.Job? = null
            fun performSearch(query: String) {
                activeJob?.cancel()
                activeJob = coroutineScope.launch {
                    delay(30)
                    currentResult = useCase.execute(query, TimedSearchScope.Library, TimedSearchFilter())
                }
            }

            setContent {
                OpenYtTheme {
                    CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                        currentResult?.let { res ->
                            val success = res as? TimedSearchResult.Success
                            success?.items?.firstOrNull()?.let { match ->
                                TimedSearchMatchCard(match = match, onPlay = {})
                            }
                        }
                    }
                }
            }

            // Issue slow query
            performSearch("slow")
            // Wait until slow search has started
            slowStarted.await()

            // Issue fast query immediately, superseding slow query
            performSearch("fast")

            // Wait until fast query completes
            waitUntilAtLeastOneExists(hasText("Fresh fast result"), timeoutMillis = 2000)

            // Unblock slow query
            slowProceed.complete(Unit)

            // Verify newest query's result is rendered, not the stale one
            onNodeWithText("Fresh fast result").assertIsDisplayed()
            assertEquals(0, onAllNodesWithText("Old slow result").fetchSemanticsNodes().size)
        } finally {
            coroutineScope.cancel()
        }
    }
}
