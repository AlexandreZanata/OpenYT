package com.clipbau.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clipbau.desktop.downloader.DesktopYtDlpEngine
import com.clipbau.ui.ClipBauApp

fun main() = application {
    val engine = remember { DesktopYtDlpEngine() }

    Window(
        onCloseRequest = {
            engine.close()
            exitApplication()
        },
        title = "ClipBaú",
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
    ) {
        ClipBauApp(engine = engine)
    }
}
