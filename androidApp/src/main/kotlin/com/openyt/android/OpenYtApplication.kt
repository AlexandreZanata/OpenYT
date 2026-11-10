package com.openyt.android

import android.app.Application
import android.content.Context
import com.openyt.android.downloader.AndroidMediaTagger
import com.openyt.android.downloader.AndroidYtDlpEngine
import com.openyt.android.diagnostics.AndroidDiagnosticsProvider
import com.openyt.android.diagnostics.AndroidUpdateChecker
import com.openyt.android.diagnostics.AndroidUpdateInstaller
import com.openyt.android.probe.AndroidMediaProbe
import com.openyt.android.saf.AndroidSafExporter
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.CompletedDownloadHandler
import com.openyt.core.application.DiagnosticsProvider
import com.openyt.core.application.DownloadRepository
import com.openyt.core.application.LibraryController
import com.openyt.core.application.MediaProbe
import com.openyt.core.application.SettingsController
import com.openyt.core.application.UpdateController
import com.openyt.core.application.UpdateInstaller
import com.openyt.core.application.SettingsRepository
import com.openyt.core.domain.DownloadEngine
import com.openyt.data.EncryptedCookieRepository
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.createOpenYtDatabase
import java.io.File
import java.util.Calendar
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

    val mediaProbe: MediaProbe = AndroidMediaProbe()

    val downloadsDirectory: String =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "downloads")
            .apply { mkdirs() }
            .absolutePath

    val diagnosticsProvider: DiagnosticsProvider =
        AndroidDiagnosticsProvider(appContext, engine, downloadsDirectory)

    val appVersion: String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    val updateController: UpdateController = UpdateController(
        checker = AndroidUpdateChecker(),
        currentVersion = appVersion,
        scope = appScope,
        hasPlatformAsset = { info ->
            info.assets.any { it.name.endsWith(".apk", ignoreCase = true) }
        },
    )

    val updateInstaller: UpdateInstaller = AndroidUpdateInstaller(appContext)

    private val database = runCatching { createOpenYtDatabase(appContext) }
        .onFailure { android.util.Log.e("OpenYT", "database initialization failed: ${'$'}{it.message}") }

    val repository: DownloadRepository? = database.getOrNull()?.let(::SqlDelightDownloadRepository)

    val settingsRepository: SettingsRepository? =
        database.getOrNull()?.let(::SqlDelightSettingsRepository)

    val settingsController: SettingsController = SettingsController(settingsRepository, appScope)

    val cookieRepository = EncryptedCookieRepository(File(appContext.filesDir, "cookies"))
    val mediaTagger = AndroidMediaTagger()

    private val safExporter = AndroidSafExporter(appContext)

    val coordinator: DownloadCoordinator = DownloadCoordinator(
        engine = engine,
        scope = appScope,
        repository = repository,
        cookieRepository = cookieRepository,
        timeOfDayMinutes = {
            val now = Calendar.getInstance()
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        },
        completedHandler = CompletedDownloadHandler { task ->
            val tree = settingsController.settings.value.storageTreeUri
            val path = task.filePath
            if (tree != null && path != null) {
                val exported = safExporter.export(path, tree)
                if (!exported) {
                    android.util.Log.w("OpenYT", "SAF export failed")
                }
            }
        },
    )

    val libraryController: LibraryController = LibraryController(repository, appScope)

    init {
        cleanStaleTemporaryFiles(File(downloadsDirectory))
        appScope.launch {
            coordinator.restore()
            settingsController.restore()
            libraryController.restore()
            cleanStaleTemporaryFiles(
                File(settingsController.settings.value.downloadsDirectory ?: downloadsDirectory),
            )
        }
    }
}

private fun cleanStaleTemporaryFiles(directory: File) {
    val cutoff = System.currentTimeMillis() - STALE_TEMP_MILLIS
    directory.listFiles { file ->
        file.isDirectory &&
            file.name.startsWith(".openyt-tmp-") &&
            file.lastModified() < cutoff
    }?.forEach { it.deleteRecursively() }
}

private const val STALE_TEMP_MILLIS = 7L * 24 * 60 * 60 * 1000
