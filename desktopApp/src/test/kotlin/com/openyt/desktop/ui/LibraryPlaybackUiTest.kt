package com.openyt.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.openyt.core.application.*
import com.openyt.core.domain.*
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.LibraryScreen
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LibraryPlaybackUiTest {
    @Test fun subtitleRecordsDoNotBecomePlayerQueueItems() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = object : DownloadEngine by SuspendedDownloadEngine() {
            override fun download(request: DownloadRequest, downloadId: String) =
                flowOf(DownloadEvent.Completed("/fixture/$downloadId", 100))
        }
        val coordinator = DownloadCoordinator(engine, scope)
        var played = emptyList<PlayableMedia>()
        val launcher = object : PlaybackLauncher {
            override val canPlay = true
            override fun play(items: List<PlayableMedia>, startIndex: Int) { played = items }
        }
        for (kind in listOf(MediaKind.SUBTITLE, MediaKind.VIDEO)) {
            val url = "https://example.com/${kind.name}"
            coordinator.enqueue(MediaInfo(kind.name, "fixture", kind.name, webpageUrl = url), DownloadRequest(url, kind, destinationDirectory = "/fixture"))
        }
        try {
            setContent {
                OpenYtTheme {
                    CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                        LibraryScreen(coordinator, FileAvailability { true }, LibraryController(null, scope),
                            MediaProbe { null }, NoopMediaActions, null, "/fixture", playbackLauncher = launcher)
                    }
                }
            }
            onNodeWithText(PtBrStrings.playAction).performClick()
            assertEquals(listOf(MediaKind.VIDEO), played.map { it.kind })
        } finally { scope.cancel() }
    }
}
