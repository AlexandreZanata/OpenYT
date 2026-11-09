package com.clipbau.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clipbau.core.application.DownloadCoordinator
import com.clipbau.desktop.downloader.DesktopYtDlpEngine
import com.clipbau.ui.ClipBauApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main() = application {
    val engine = remember { DesktopYtDlpEngine() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val coordinator = remember(engine) { DownloadCoordinator(engine, scope) }
    val downloadsDirectory = remember { defaultDownloadsDirectory() }

    Window(
        onCloseRequest = {
            engine.close()
            scope.cancel()
            exitApplication()
        },
        title = "ClipBaú",
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
    ) {
        ClipBauApp(
            engine = engine,
            coordinator = coordinator,
            downloadsDirectory = downloadsDirectory,
        )
    }
}

private fun defaultDownloadsDirectory(): String {
    val home = System.getProperty("user.home") ?: "."
    val preferred = File(home, "Downloads/ClipBaú")
    val directory = if (preferred.mkdirs() || preferred.isDirectory) {
        preferred
    } else {
        File(System.getProperty("java.io.tmpdir"), "clipbau-downloads")
    }
    directory.mkdirs()
    return directory.absolutePath
}
