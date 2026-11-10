package com.openyt.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaFormat
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.PlaylistEntry
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.FormatPickerDialog
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomePlaylistUiTest {
    @Test
    fun entryFormatDialogLoadsFormats() = runComposeUiTest {
        setContent {
            OpenYtTheme(darkTheme = true) {
                CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                    FormatPickerDialog(
                        entry = PlaylistEntry(
                            index = 1,
                            title = "Entrada 1",
                            url = "https://example.com/entry/1",
                        ),
                        engine = PlaylistEngine(),
                        defaultKind = MediaKind.VIDEO,
                        current = null,
                        onDismiss = {},
                        onClear = {},
                        onSelect = {},
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Melhor disponível").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithText("Entrada 1").fetchSemanticsNodes().isNotEmpty(),
            "dialog title missing",
        )
    }
}

private class PlaylistEngine : DownloadEngine {
    override suspend fun inspect(url: String): EngineResult<Inspection> = EngineResult.Success(
        Inspection.Single(
            MediaInfo(
                sourceId = url,
                source = "youtube",
                title = "Entrada 1",
                webpageUrl = url,
                formats = listOf(
                    MediaFormat("v1", MediaKind.VIDEO, "mp4", height = 720),
                    MediaFormat("a1", MediaKind.AUDIO, "m4a", audioCodec = "aac"),
                ),
            ),
        ),
    )

    override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Starting)
        emit(DownloadEvent.Completed("/tmp/$downloadId.bin", 1))
    }

    override suspend fun cancel(downloadId: String) = Unit

    override suspend fun capabilities(): EngineCapabilities =
        EngineCapabilities("test", "0", supportsPause = true, supportsSubtitles = false)

    override fun close() = Unit
}
