package com.openyt.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.FolderImportService
import com.openyt.core.application.FolderPicker
import com.openyt.core.application.LibraryArchiveService
import com.openyt.core.application.LibraryController
import com.openyt.core.application.SettingsController
import com.openyt.core.application.UpdateController
import com.openyt.data.EncryptedCookieRepository
import com.openyt.data.FileFolderScanner
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.defaultDatabasePath
import com.openyt.desktop.BuildInfo
import com.openyt.desktop.diagnostics.DesktopDiagnosticsProvider
import com.openyt.desktop.diagnostics.DesktopUpdateChecker
import com.openyt.desktop.diagnostics.DesktopUpdateInstaller
import com.openyt.desktop.cli.DesktopCli
import com.openyt.desktop.diagnostics.DesktopUrlOpener
import com.openyt.desktop.downloader.DesktopMediaProbe
import com.openyt.desktop.downloader.DesktopMediaTagger
import com.openyt.desktop.downloader.DesktopYtDlpEngine
import com.openyt.desktop.share.DesktopMediaActions
import com.openyt.ui.OpenYtApp
import com.openyt.ui.components.BrandIconPainter
import java.io.File
import java.time.LocalTime
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--cli") {
        exitProcess(DesktopCli.run(args.drop(1)))
    }
    launchApp()
}

private fun launchApp() = application {
    val engine = remember { DesktopYtDlpEngine() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val database = remember {
        runCatching { createOpenYtDatabase(defaultDatabasePath()) }
            .onFailure { System.err.println("OpenYT: database initialization failed: ${it.message}") }
            .getOrNull()
    }
    val repository = remember(database) { database?.let(::SqlDelightDownloadRepository) }
    val settingsController = remember(database) {
        SettingsController(database?.let(::SqlDelightSettingsRepository), scope)
    }
    val cookieRepository = remember {
        EncryptedCookieRepository(File(defaultDatabasePath()).parentFile.resolve("cookies"))
    }
    val coordinator = remember(engine) {
        DownloadCoordinator(
            engine = engine,
            scope = scope,
            repository = repository,
            cookieRepository = cookieRepository,
            timeOfDayMinutes = {
                val now = LocalTime.now()
                now.hour * 60 + now.minute
            },
        )
    }
    val libraryController = remember(repository) { LibraryController(repository, scope) }
    val libraryArchiveService = remember(repository) { LibraryArchiveService(repository) }
    val folderImportService = remember(repository) {
        FolderImportService(FileFolderScanner(), repository)
    }
    val mediaProbe = remember { DesktopMediaProbe() }
    val mediaTagger = remember { DesktopMediaTagger() }
    val mediaActions = remember { DesktopMediaActions() }
    val downloadsDirectory = remember { defaultDownloadsDirectory() }
    val diagnosticsProvider = remember {
        DesktopDiagnosticsProvider(engine, downloadsDirectory)
    }
    val updateController = remember {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val suffix = when {
            os.contains("win") -> ".msi"
            os.contains("mac") -> ".dmg"
            else -> ".deb"
        }
        UpdateController(
            checker = DesktopUpdateChecker(),
            currentVersion = BuildInfo.version,
            scope = scope,
            hasPlatformAsset = { info ->
                info.assets.any { it.name.endsWith(suffix, ignoreCase = true) }
            },
        )
    }
    val updateInstaller = remember { DesktopUpdateInstaller() }
    val urlOpener = remember { DesktopUrlOpener() }
    val folderPicker = remember { FolderPicker { current -> DesktopFolderPicker.pick(current) } }
    var sharedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(coordinator, settingsController, libraryController) {
        coordinator.restore()
        settingsController.restore()
        libraryController.restore()
        cleanStaleTemporaryFiles(
            File(settingsController.settings.value.downloadsDirectory ?: downloadsDirectory),
        )
    }

    Window(
        onCloseRequest = {
            engine.close()
            scope.cancel()
            exitApplication()
        },
        title = "OpenYT",
        icon = BrandIconPainter(),
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
        onPreviewKeyEvent = { event ->
            if (
                event.type == KeyEventType.KeyDown &&
                event.isCtrlPressed &&
                event.key == Key.Q
            ) {
                engine.close()
                scope.cancel()
                exitApplication()
                true
            } else {
                false
            }
        },
    ) {
        LaunchedEffect(window) {
            window.toFront()
            window.requestFocus()
        }

        DisposableEffect(window) {
            window.dropTarget = DesktopDropTarget { url ->
                SwingUtilities.invokeLater { sharedUrl = url }
                window.toFront()
            }
            onDispose { window.dropTarget = null }
        }

        OpenYtApp(
            engine = engine,
            coordinator = coordinator,
            settingsController = settingsController,
            libraryController = libraryController,
            mediaProbe = mediaProbe,
            mediaActions = mediaActions,
            diagnosticsProvider = diagnosticsProvider,
            updateController = updateController,
            updateInstaller = updateInstaller,
            urlOpener = urlOpener,
            downloadsDirectory = downloadsDirectory,
            fileAvailability = FileAvailability { File(it).isFile },
            initialSharedUrl = sharedUrl,
            onSharedUrlConsumed = { sharedUrl = null },
            folderPicker = folderPicker,
            cookieRepository = cookieRepository,
            nowEpochMillis = { System.currentTimeMillis() },
            mediaTagger = mediaTagger,
            libraryArchiveService = libraryArchiveService,
            folderImportService = folderImportService,
        )
    }
}
