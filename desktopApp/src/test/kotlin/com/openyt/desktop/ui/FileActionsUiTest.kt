package com.openyt.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.openyt.core.application.*
import com.openyt.core.domain.*
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.DownloadsScreen
import com.openyt.ui.screens.LibraryScreen
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FileActionsUiTest {
    @Test fun libraryOpenFailureIsVisible() = verify(library = true, reveal = false)
    @Test fun libraryRevealFailureIsVisible() = verify(library = true, reveal = true)
    @Test fun downloadsOpenFailureIsVisible() = verify(library = false, reveal = false)
    @Test fun downloadsRevealFailureIsVisible() = verify(library = false, reveal = true)

    private fun verify(library: Boolean, reveal: Boolean) = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val path = "/fixture/Vídeo & aula.mp4"
        val engine = object : DownloadEngine by SuspendedDownloadEngine() {
            override fun download(request: DownloadRequest, downloadId: String) = flowOf(DownloadEvent.Completed(path, 100))
        }
        val coordinator = DownloadCoordinator(engine, scope)
        var requested: String? = null
        val actions = object : MediaActions {
            override val canShare = false
            override val canOpen = true
            override val canReveal = true
            override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit
            override fun open(media: ShareableMedia): Boolean { requested = media.path; return false }
            override fun reveal(media: ShareableMedia): Boolean { requested = media.path; return false }
        }
        coordinator.enqueue(MediaInfo("fixture", "fixture", "Video", webpageUrl = "https://example.com/video"),
            DownloadRequest("https://example.com/video", MediaKind.VIDEO, destinationDirectory = "/fixture"))
        try {
            setContent {
                OpenYtTheme {
                    CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                        if (library) LibraryScreen(coordinator, FileAvailability { true }, LibraryController(null, scope),
                            MediaProbe { null }, actions, null, "/fixture")
                        else DownloadsScreen(coordinator, actions)
                    }
                }
            }
            onNodeWithText(if (reveal) PtBrStrings.revealAction else PtBrStrings.openAction).performClick()
            val message = PtBrStrings.features[if (reveal) FeatureText.REVEAL_FILE_FAILED else FeatureText.OPEN_FILE_FAILED]
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(path, requested)
            onNodeWithText(message).assertIsDisplayed()
            onNodeWithText(PtBrStrings.closeAction).performClick()
        } finally { scope.cancel() }
    }
}
