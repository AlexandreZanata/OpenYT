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
import com.openyt.core.application.CompletedDownloadHandler
import com.openyt.core.application.DiagnosticsProvider
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.DownloadProfileRepository
import com.openyt.core.application.DownloadRepository
import com.openyt.core.application.SafExportRouter
import com.openyt.core.application.LibraryArchiveService
import com.openyt.core.application.BulkFileService
import com.openyt.core.application.FolderImportService
import com.openyt.core.application.LibraryController
import com.openyt.core.application.MediaProbe
import com.openyt.core.application.SettingsController
import com.openyt.core.application.UpdateController
import com.openyt.core.application.UpdateInstaller
import com.openyt.core.application.SettingsRepository
import com.openyt.core.domain.DownloadEngine
import com.openyt.data.EncryptedCookieRepository
import com.openyt.data.FileBulkFileOperator
import com.openyt.data.FileFolderScanner
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightDownloadProfileRepository
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightAssetOperationRepository
import com.openyt.data.JvmOfflineAssetStore
import com.openyt.data.JvmMediaFileInspector
import com.openyt.data.JvmArtworkFetchPort
import com.openyt.core.application.ArtworkFetchService
import com.openyt.core.application.SubtitleImportService
import com.openyt.core.application.SubtitleIndexRepository
import com.openyt.core.application.TimedSearchRepository
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.core.application.OfflineAssetRepository
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyCaptureAndNavigationService
import com.openyt.core.application.StudyEditorCoordinator
import com.openyt.core.application.StudyRepository
import com.openyt.core.application.StudyService
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.SqlDelightSubscriptions
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.search.SqlDelightTimedSearchRepository
import com.openyt.data.RssFeedReader
import com.openyt.core.automation.SubscriptionController
import com.openyt.core.application.MemoryPlaybackStore
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
    val profileRepository: DownloadProfileRepository? =
        database.getOrNull()?.let(::SqlDelightDownloadProfileRepository)

    val offlineAssetsDirectory: File =
        File(appContext.filesDir, "offline-assets").apply { mkdirs() }
    val offlineAssetRepository: OfflineAssetRepository? =
        database.getOrNull()?.let(::SqlDelightOfflineAssetRepository)
    val offlineAssetStore: JvmOfflineAssetStore? = run {
        val db = database.getOrNull() ?: return@run null
        val assetRepository = offlineAssetRepository ?: return@run null
        JvmOfflineAssetStore(
            assetsDirectory = offlineAssetsDirectory,
            repository = assetRepository,
            operationRepository = SqlDelightAssetOperationRepository(db),
            storageReserveBytesProvider = { settingsController.settings.value.storageReserveBytes },
            assetBudgetBytesProvider = { settingsController.settings.value.assetBudgetBytes },
        )
    }
    val artworkFetchService: ArtworkFetchService? = run {
        val store = offlineAssetStore ?: return@run null
        val assetRepository = offlineAssetRepository ?: return@run null
        ArtworkFetchService(
            operator = store.operator,
            fetchPort = JvmArtworkFetchPort(),
            assetRepository = assetRepository,
            stagingPort = store.stagingPort,
        )
    }
    val subtitleImportService: SubtitleImportService? = run {
        val store = offlineAssetStore ?: return@run null
        val assetRepository = offlineAssetRepository ?: return@run null
        SubtitleImportService(
            operator = store.operator,
            assetRepository = assetRepository,
            stagingPort = store.stagingPort,
            fileInspector = JvmMediaFileInspector(),
        )
    }
    val subtitleIndexRepository: SubtitleIndexRepository? =
        database.getOrNull()?.let(::SqlDelightSubtitleIndexRepository)
    val timedSearchRepository: TimedSearchRepository? =
        database.getOrNull()?.let(::SqlDelightTimedSearchRepository)
    val timedSearchUseCase: TimedSearchUseCase? =
        timedSearchRepository?.let(::TimedSearchUseCase)
    val renderingCacheBytes: () -> Long = { appContext.cacheDir.directorySize() }

    val playbackStore = database.getOrNull()?.let(::SqlDelightPlaybackStore) ?: MemoryPlaybackStore()

    val studyRepository: StudyRepository? =
        database.getOrNull()?.let(::SqlDelightStudyRepository)
    val studyService: StudyService? =
        studyRepository?.let { repo ->
            StudyService(
                repository = repo,
                nowEpochMillis = { System.currentTimeMillis() },
                nextId = { java.util.UUID.randomUUID().toString() },
            )
        }
    val studyEditorCoordinator: StudyEditorCoordinator = StudyEditorCoordinator()

    fun createStudyCaptureAndNavigationService(
        playbackLauncher: PlaybackLauncher?,
    ): StudyCaptureAndNavigationService? {
        val service = studyService ?: return null
        return StudyCaptureAndNavigationService(
            studyService = service,
            playbackLauncher = playbackLauncher,
            playbackStore = playbackStore,
            entryProvider = { coordinator.library.value },
            fileAvailability = FileAvailability { File(it).isFile },
            mediaProbe = mediaProbe,
            nowEpochMillis = { System.currentTimeMillis() },
        )
    }

    fun createStudyAbRepeatController(
        playbackLauncher: PlaybackLauncher?,
        scope: CoroutineScope = appScope,
    ): StudyAbRepeatController =
        StudyAbRepeatController(
            playbackLauncher = playbackLauncher,
            scope = scope,
        )

    val settingsRepository: SettingsRepository? =
        database.getOrNull()?.let(::SqlDelightSettingsRepository)

    val settingsController: SettingsController = SettingsController(settingsRepository, appScope)

    val cookieRepository = EncryptedCookieRepository(File(appContext.filesDir, "cookies"))
    val mediaTagger = AndroidMediaTagger(appContext)

    private val safExporter = AndroidSafExporter(appContext)
    private val safExportRouter = SafExportRouter(
        exporter = safExporter,
        fallbackTreeUri = { settingsController.settings.value.storageTreeUri },
        onWarning = { warning ->
            android.util.Log.w("OpenYT", warning.message)
        },
    )

    val coordinator: DownloadCoordinator = DownloadCoordinator(
        engine = engine,
        scope = appScope,
        repository = repository,
        cookieRepository = cookieRepository,
        assetStore = offlineAssetStore,
        timeOfDayMinutes = {
            val now = Calendar.getInstance()
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        },
        completedHandler = safExportRouter,
    )

    val subscriptions = database.getOrNull()?.let { database ->
        SubscriptionController(SqlDelightSubscriptions(database), RssFeedReader(), engine, coordinator,
            { settingsController.settings.value.downloadsDirectory ?: downloadsDirectory },
            { System.currentTimeMillis() }, { java.util.UUID.randomUUID().toString() }, appScope)
    }

    val libraryController: LibraryController = LibraryController(repository, appScope)
    val libraryArchiveService = LibraryArchiveService(repository)
    val folderImportService = FolderImportService(FileFolderScanner(), repository)
    val bulkFileService = BulkFileService(FileBulkFileOperator(), repository)
    val portableBackup: com.openyt.data.PortableBackupRuntime? = database.getOrNull()?.let { db ->
        com.openyt.data.createPortableBackupRuntime(
            database = db,
            stagingRoot = File(appContext.filesDir, "portable-staging"),
            ownedAssetsRoot = offlineAssetsDirectory,
            streamBridge = com.openyt.data.AndroidPortableStreamBridge(appContext),
            defaultDestinationRoot = {
                settingsController.settings.value.downloadsDirectory ?: downloadsDirectory
            },
            offlineAssetStore = offlineAssetStore,
            subtitleIndexRepository = subtitleIndexRepository,
            appVersion = appVersion,
        )
    }

    fun createLibraryRecoveryController(
        playbackSessionControl: com.openyt.core.application.PlaybackSessionControl? = null,
    ): com.openyt.core.application.LibraryRecoveryUiController? {
        val db = database.getOrNull() ?: return null
        val repo = repository ?: return null
        return com.openyt.data.createLibraryRecoveryRuntime(
            database = db,
            downloadRepository = repo,
            offlineAssetRepository = offlineAssetRepository,
            subtitleIndexRepository = subtitleIndexRepository,
            studyRepository = studyRepository,
            stagingRoot = File(appContext.filesDir, "portable-staging"),
            ownedRecoveryRootProvider = {
                settingsController.settings.value.downloadsDirectory ?: downloadsDirectory
            },
            streamBridge = com.openyt.data.AndroidPortableStreamBridge(appContext),
            healthInspector = com.openyt.data.AndroidMediaHealthInspector(appContext),
            candidateSourcePort = com.openyt.data.AndroidRecoveryCandidateScanner(appContext),
            playbackSessionControl = playbackSessionControl,
        ).controller
    }

    init {
        cleanStaleTemporaryFiles(File(downloadsDirectory))
        appScope.launch {
            coordinator.restore()
            settingsController.restore()
            libraryController.restore()
            subscriptions?.restore()
            subscriptions?.start()
            cleanStaleTemporaryFiles(
                File(settingsController.settings.value.downloadsDirectory ?: downloadsDirectory),
            )
            portableBackup?.reconcileOnStartup()
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

private fun File.directorySize(): Long =
    if (isDirectory) {
        listFiles()?.sumOf { it.directorySize() } ?: 0L
    } else {
        length()
    }
