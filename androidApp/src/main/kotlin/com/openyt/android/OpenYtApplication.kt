package com.openyt.android

import android.app.Application
import android.content.Context
import com.openyt.android.downloader.AndroidYtDlpEngine
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.DownloadRepository
import com.openyt.core.application.SettingsController
import com.openyt.core.application.SettingsRepository
import com.openyt.core.domain.DownloadEngine
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.createOpenYtDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class OpenYtApplication : Application() {
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

    private val database = runCatching { createOpenYtDatabase(appContext) }
        .onFailure { android.util.Log.e("OpenYT", "database initialization failed: ${'$'}{it.message}") }

    val repository: DownloadRepository? = database.getOrNull()?.let(::SqlDelightDownloadRepository)

    val coordinator: DownloadCoordinator = DownloadCoordinator(engine, appScope, repository)

    val settingsRepository: SettingsRepository? =
        database.getOrNull()?.let(::SqlDelightSettingsRepository)

    val settingsController: SettingsController = SettingsController(settingsRepository, appScope)

    init {
        cleanStaleTemporaryFiles(File(downloadsDirectory))
        appScope.launch {
            coordinator.restore()
            settingsController.restore()
        }
    }
}

private fun cleanStaleTemporaryFiles(directory: File) {
    directory.listFiles { file -> file.isDirectory && file.name.startsWith(".openyt-tmp-") }
        ?.forEach { it.deleteRecursively() }
}
