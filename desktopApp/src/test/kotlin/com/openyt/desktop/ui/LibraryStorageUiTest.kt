package com.openyt.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.FolderImportService
import com.openyt.core.application.FolderScanner
import com.openyt.core.application.LibraryController
import com.openyt.core.application.MediaProbe
import com.openyt.core.application.ScannedMedia
import com.openyt.core.domain.MediaKind
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.LibraryScreen
import com.openyt.ui.theme.OpenYtTheme
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LibraryStorageUiTest {
    @Test
    fun failedScanCanBeRetriedFromTheLibrary() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DownloadCoordinator(SuspendedDownloadEngine(), scope)
        val libraryController = LibraryController(null, scope)
        var scans = 0
        val folders = FolderImportService(
            scanner = FolderScanner {
                scans++
                if (scans == 1) throw IOException("private diagnostic")
                listOf(ScannedMedia("C:\\Downloads\\Aula Básica.mkv", "Aula", MediaKind.VIDEO, 100))
            },
            repository = null,
        )
        try {
            setContent {
                OpenYtTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalStrings provides PtBrStrings) {
                        LibraryScreen(
                            coordinator = coordinator,
                            fileAvailability = FileAvailability { true },
                            libraryController = libraryController,
                            mediaProbe = MediaProbe { null },
                            mediaActions = NoopMediaActions,
                            mediaImporter = null,
                            downloadsDirectory = "C:\\Downloads",
                            folderImportService = folders,
                        )
                    }
                }
            }
            onNodeWithText(PtBrStrings.storagePanel).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(PtBrStrings.storageFailed)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(PtBrStrings.retryAction).assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(PtBrStrings.storageOrphanHint)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(PtBrStrings.closeAction).assertIsDisplayed().performClick()
            onNodeWithText(PtBrStrings.libraryEmpty).assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }
}
