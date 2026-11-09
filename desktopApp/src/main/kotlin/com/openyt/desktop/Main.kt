package com.openyt.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.openyt.core.application.DownloadCoordinator
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.defaultDatabasePath
import com.openyt.desktop.downloader.DesktopYtDlpEngine
import com.openyt.ui.OpenYtApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main() = application {
    val engine = remember { DesktopYtDlpEngine() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val repository = remember {
        runCatching { SqlDelightDownloadRepository(createOpenYtDatabase(defaultDatabasePath())) }
            .onFailure { System.err.println("OpenYT: database initialization failed: ${'$'}{it.message}") }
            .getOrNull()
    }
    val coordinator = remember(engine) { DownloadCoordinator(engine, scope, repository) }
    val downloadsDirectory = remember { defaultDownloadsDirectory() }

    LaunchedEffect(coordinator) { coordinator.restore() }

    Window(
        onCloseRequest = {
            engine.close()
            scope.cancel()
            exitApplication()
        },
        title = "OpenYT",
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
    ) {
        OpenYtApp(
            engine = engine,
            coordinator = coordinator,
            downloadsDirectory = downloadsDirectory,
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
    directory.listFiles { file -> file.isDirectory && file.name.startsWith(".openyt-tmp-") }
        ?.forEach { it.deleteRecursively() }
    return directory.absolutePath
}
