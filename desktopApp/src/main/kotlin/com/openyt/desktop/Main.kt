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
import com.openyt.core.application.FolderPicker
import com.openyt.core.application.SettingsController
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.defaultDatabasePath
import com.openyt.desktop.downloader.DesktopYtDlpEngine
import com.openyt.ui.OpenYtApp
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main() = application {
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
    val coordinator = remember(engine) { DownloadCoordinator(engine, scope, repository) }
    val downloadsDirectory = remember { defaultDownloadsDirectory() }
    val folderPicker = remember { FolderPicker { current -> DesktopFolderPicker.pick(current) } }
    var sharedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(coordinator, settingsController) {
        coordinator.restore()
        settingsController.restore()
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
            downloadsDirectory = downloadsDirectory,
            fileAvailability = FileAvailability { File(it).isFile },
            initialSharedUrl = sharedUrl,
            onSharedUrlConsumed = { sharedUrl = null },
            folderPicker = folderPicker,
        )
    }
}

private fun defaultDownloadsDirectory(): String {
    val home = System.getProperty("user.home") ?: "."
    val preferred = File(home, "Downloads/OpenYT")
    val directory = if (preferred.mkdirs() || preferred.isDirectory) {
        preferred
    } else {
        File(System.getProperty("java.io.tmpdir"), "openyt-downloads")
    }
    directory.mkdirs()
    cleanStaleTemporaryFiles(directory)
    return directory.absolutePath
}

private fun cleanStaleTemporaryFiles(directory: File) {
    if (!directory.isDirectory) return
    directory.listFiles { file -> file.isDirectory && file.name.startsWith(".openyt-tmp-") }
        ?.forEach { it.deleteRecursively() }
}
