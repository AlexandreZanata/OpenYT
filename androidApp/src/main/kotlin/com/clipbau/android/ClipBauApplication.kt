package com.clipbau.android

import android.app.Application
import android.content.Context
import com.clipbau.android.downloader.AndroidYtDlpEngine
import com.clipbau.core.application.DownloadCoordinator
import com.clipbau.core.domain.DownloadEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ClipBauApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: DownloadEngine = AndroidYtDlpEngine(appContext)

    val downloadsDirectory: String =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "downloads")
            .apply { mkdirs() }
            .absolutePath

    val coordinator: DownloadCoordinator = DownloadCoordinator(engine, appScope)
}
