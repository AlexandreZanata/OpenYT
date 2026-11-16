package com.openyt.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.FolderImportService
import com.openyt.core.application.FolderPicker
import com.openyt.core.application.BulkFileService
import com.openyt.core.application.LibraryArchiveService
import com.openyt.core.application.LibraryController
import com.openyt.core.application.SettingsController
import com.openyt.core.application.UpdateController
import com.openyt.data.EncryptedCookieRepository
import com.openyt.data.FileBulkFileOperator
import com.openyt.data.FileFolderScanner
import com.openyt.data.LibraryRecoveryRuntime
import com.openyt.data.PortableBackupRuntime
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightDownloadProfileRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightAssetOperationRepository
import com.openyt.data.JvmOfflineAssetStore
import com.openyt.data.JvmMediaFileInspector
import com.openyt.data.JvmArtworkFetchPort
import com.openyt.core.application.ArtworkFetchService
import com.openyt.core.application.DownloadProfileRepository
import com.openyt.core.application.SubtitleImportService
import com.openyt.core.application.SubtitleIndexRepository
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightSubscriptions
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.search.SqlDelightTimedSearchRepository
import com.openyt.data.RssFeedReader
import com.openyt.core.application.MemoryDownloadProfileRepository
import com.openyt.core.automation.SubscriptionController
import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.domain.AppLanguage
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.stringsFor
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.defaultDatabasePath
import com.openyt.desktop.BuildInfo
import com.openyt.desktop.diagnostics.DesktopDiagnosticsProvider
import com.openyt.desktop.diagnostics.DesktopUpdateChecker
import com.openyt.desktop.diagnostics.DesktopUpdateInstaller
import com.openyt.desktop.cli.DesktopCli
import com.openyt.desktop.diagnostics.DesktopUrlOpener
import com.openyt.desktop.downloader.BundledTools
import com.openyt.desktop.downloader.DesktopMediaProbe
import com.openyt.desktop.downloader.DesktopMediaTagger
import com.openyt.desktop.downloader.DesktopYtDlpEngine
import com.openyt.desktop.player.DesktopPlaybackLauncher
import com.openyt.desktop.share.DesktopMediaActions
import com.openyt.ui.OpenYtApp
import com.openyt.ui.components.StartupLoadingScreen
import com.openyt.ui.theme.OpenYtTheme
import com.openyt.core.application.EngineMediaPreview
import com.openyt.core.application.OpenYtLink
import com.openyt.core.application.OpenYtRoute
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyCaptureAndNavigationService
import com.openyt.core.application.StudyEditorCoordinator
import com.openyt.core.application.StudyHostLinkCapability
import com.openyt.core.application.StudyService
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.media.ffmpeg.FfmpegMediaProcessor
import com.openyt.desktop.downloader.ExecutableLocator
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
    val cli = args.firstOrNull() == "--cli"
    if (cli && args.getOrNull(1) in setOf("version", "link")) exitProcess(DesktopCli.run(args.drop(1)))
    val initialRoute = if (cli) null else args.firstNotNullOfOrNull(OpenYtLink::parseRoute)
    val root = File(defaultDatabasePath()).parentFile
    val instance = runCatching { DesktopInstance.acquire(root, gui = !cli) }.getOrNull()
    if (instance == null) {
        if (cli && args.getOrNull(1) == "watch" && "--discover-only" in args) {
            println("Subscription checks are owned by the running application; no second coordinator started")
            return
        }
        if (!cli && DesktopInstance.forwardRoute(root, initialRoute)) return
        val text = if (java.util.Locale.getDefault().language == "pt")
            "Não foi possível acessar os dados do OpenYT. Feche outra instância ou verifique o armazenamento."
        else "Could not access OpenYT data. Close another instance or check storage."
        if (cli) System.err.println("error: $text")
        else javax.swing.JOptionPane.showMessageDialog(null, text, "OpenYT", javax.swing.JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    if (cli) {
        val result = instance.use { DesktopCli.run(args.drop(1)) }
        exitProcess(result)
    }
    val deepLinksCheck = kotlinx.coroutines.CompletableDeferred<Boolean>()
    Thread({
        val supported = runCatching {
            val registered = DesktopDeepLinks.register()
            if (!registered) {
                System.err.println(
                    "OpenYT: DesktopDeepLinks.register() returned false (jpackage.app-path=${System.getProperty("jpackage.app-path")})",
                )
            }
            DesktopDeepLinks.isEntryLinkDispatchSupported()
        }.onFailure { err ->
            System.err.println("OpenYT: DesktopDeepLinks registration failed: ${err::class.simpleName}: ${err.message}")
        }.getOrDefault(false)
        deepLinksCheck.complete(supported)
    }, "openyt-deeplinks").apply { isDaemon = true; start() }
    instance.use { owned ->
        val engine = DesktopYtDlpEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtimeTask = scope.async(Dispatchers.IO) {
            createDesktopRuntime(engine = engine, scope = scope)
        }
        try {
            launchApp(initialRoute, owned, deepLinksCheck, engine, scope, runtimeTask)
        } finally {
            scope.cancel()
            engine.close()
        }
    }
}

private class DesktopRuntime(
    val settingsController: SettingsController,
    val offlineAssetStore: JvmOfflineAssetStore?,
    val offlineAssetRepository: SqlDelightOfflineAssetRepository?,
    val artworkFetchService: ArtworkFetchService?,
    val subtitleImportService: SubtitleImportService?,
    val subtitleIndexRepository: SubtitleIndexRepository?,
    val timedSearchUseCase: TimedSearchUseCase?,
    val previewsDirectory: File,
    val profileRepository: DownloadProfileRepository,
    val cookieRepository: EncryptedCookieRepository,
    val coordinator: DownloadCoordinator,
    val libraryController: LibraryController,
    val libraryArchiveService: LibraryArchiveService,
    val folderImportService: FolderImportService,
    val bulkFileService: BulkFileService,
    val mediaProbe: DesktopMediaProbe,
    val mediaTagger: DesktopMediaTagger,
    val playbackLauncher: DesktopPlaybackLauncher,
    val mediaActions: DesktopMediaActions,
    val downloadsDirectory: String,
    val portableBackup: PortableBackupRuntime?,
    val portableArchivePicker: DesktopPortableArchivePicker,
    val subscriptions: SubscriptionController?,
    val diagnosticsProvider: DesktopDiagnosticsProvider,
    val updateController: UpdateController,
    val updateInstaller: DesktopUpdateInstaller,
    val urlOpener: DesktopUrlOpener,
    val folderPicker: FolderPicker,
    val recoveryRuntime: LibraryRecoveryRuntime?,
    val recoveryLocationPicker: DesktopRecoveryLocationPicker,
    val studyService: StudyService?,
    val studyEditorCoordinator: StudyEditorCoordinator,
    val studyCaptureService: StudyCaptureAndNavigationService?,
    val studyAbRepeatController: StudyAbRepeatController,
    val mediaProcessor: FfmpegMediaProcessor,
    val mediaPreview: EngineMediaPreview,
    val toolUpdater: com.openyt.core.application.ToolUpdater,
    val offlineAssetInspector: JvmMediaFileInspector,
    val subtitlePicker: com.openyt.core.application.SubtitlePicker,
)

private suspend fun createDesktopRuntime(
    engine: DesktopYtDlpEngine,
    scope: CoroutineScope,
): DesktopRuntime = withContext(Dispatchers.IO) {
    val database = runCatching { createOpenYtDatabase(defaultDatabasePath()) }
        .onFailure { System.err.println("OpenYT: database initialization failed (${it::class.simpleName})") }
        .getOrNull()
    val repository = database?.let(::SqlDelightDownloadRepository)
    val settingsController = SettingsController(database?.let(::SqlDelightSettingsRepository), scope)
    val dbDirectory = File(defaultDatabasePath()).parentFile
    val offlineAssetsDirectory = dbDirectory.resolve("offline-assets").apply { mkdirs() }
    val offlineAssetRepository = database?.let(::SqlDelightOfflineAssetRepository)
    val offlineAssetStore = if (database != null && offlineAssetRepository != null) {
        JvmOfflineAssetStore(
            assetsDirectory = offlineAssetsDirectory,
            repository = offlineAssetRepository,
            operationRepository = SqlDelightAssetOperationRepository(database),
            storageReserveBytesProvider = { settingsController.settings.value.storageReserveBytes },
            assetBudgetBytesProvider = { settingsController.settings.value.assetBudgetBytes },
        )
    } else {
        null
    }
    val mediaFileInspector = JvmMediaFileInspector()
    val artworkFetchService = if (offlineAssetStore != null && offlineAssetRepository != null) {
        ArtworkFetchService(
            operator = offlineAssetStore.operator,
            fetchPort = JvmArtworkFetchPort(),
            assetRepository = offlineAssetRepository,
            stagingPort = offlineAssetStore.stagingPort,
        )
    } else {
        null
    }
    val subtitleImportService = if (offlineAssetStore != null && offlineAssetRepository != null) {
        SubtitleImportService(
            operator = offlineAssetStore.operator,
            assetRepository = offlineAssetRepository,
            stagingPort = offlineAssetStore.stagingPort,
            fileInspector = mediaFileInspector,
        )
    } else {
        null
    }
    val subtitleIndexRepository = database?.let(::SqlDelightSubtitleIndexRepository)
    val timedSearchRepository = database?.let(::SqlDelightTimedSearchRepository)
    val timedSearchUseCase = timedSearchRepository?.let(::TimedSearchUseCase)
    val previewsDirectory = File(System.getProperty("java.io.tmpdir"), "openyt-previews").apply { mkdirs() }
    val profileRepository = database?.let(::SqlDelightDownloadProfileRepository) ?: MemoryDownloadProfileRepository()
    val cookieRepository = EncryptedCookieRepository(dbDirectory.resolve("cookies"))
    val coordinator = DownloadCoordinator(
        engine = engine,
        scope = scope,
        repository = repository,
        cookieRepository = cookieRepository,
        assetStore = offlineAssetStore,
        executionGate = com.openyt.core.automation.SubscriptionDownloadGate(com.openyt.data.JvmSubscriptionEnvironment()),
        timeOfDayMinutes = {
            val now = LocalTime.now()
            now.hour * 60 + now.minute
        },
    )
    val libraryController = LibraryController(repository, scope)
    val libraryArchiveService = LibraryArchiveService(repository)
    val folderImportService = FolderImportService(FileFolderScanner(), repository)
    val bulkFileService = BulkFileService(FileBulkFileOperator(), repository)
    val mediaProbe = DesktopMediaProbe()
    val mediaTagger = DesktopMediaTagger()
    val playbackStore = database?.let(::SqlDelightPlaybackStore) ?: MemoryPlaybackStore()
    val playbackLauncher = DesktopPlaybackLauncher(
        playbackStore,
        { stringsFor(settingsController.settings.value.language, java.util.Locale.getDefault().toLanguageTag()) },
        scope,
    )
    val mediaActions = DesktopMediaActions()
    val downloadsDirectory = defaultDownloadsDirectory()
    val portableBackup = database?.let { db ->
        com.openyt.data.createPortableBackupRuntime(
            database = db,
            stagingRoot = dbDirectory.resolve("portable-staging"),
            ownedAssetsRoot = offlineAssetsDirectory,
            streamBridge = com.openyt.data.FilePortableStreamBridge(),
            defaultDestinationRoot = {
                settingsController.settings.value.downloadsDirectory ?: downloadsDirectory
            },
            offlineAssetStore = offlineAssetStore,
            subtitleIndexRepository = subtitleIndexRepository,
            appVersion = BuildInfo.version,
        )
    }
    val portableArchivePicker = DesktopPortableArchivePicker()
    val subscriptions = database?.let {
        SubscriptionController(
            SqlDelightSubscriptions(it),
            RssFeedReader(),
            engine,
            coordinator,
            { settingsController.settings.value.downloadsDirectory ?: downloadsDirectory },
            { System.currentTimeMillis() },
            { java.util.UUID.randomUUID().toString() },
            scope,
            profileRepository = SqlDelightDownloadProfileRepository(it),
            environment = com.openyt.data.JvmSubscriptionEnvironment(),
        )
    }
    val diagnosticsProvider = DesktopDiagnosticsProvider(engine, downloadsDirectory)
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val suffix = when {
        os.contains("win") -> ".msi"
        os.contains("mac") -> ".dmg"
        else -> ".deb"
    }
    val updateController = UpdateController(
        checker = DesktopUpdateChecker(),
        currentVersion = BuildInfo.version,
        scope = scope,
        hasPlatformAsset = { info -> info.assets.any { it.name.endsWith(suffix, ignoreCase = true) } },
    )
    val updateInstaller = DesktopUpdateInstaller()
    val urlOpener = DesktopUrlOpener()
    val folderPicker = FolderPicker { current -> DesktopFolderPicker.pick(current) }
    val studyRepository = database?.let(::SqlDelightStudyRepository)
    val recoveryRuntime = if (database != null && repository != null) {
        com.openyt.data.createLibraryRecoveryRuntime(
            database = database,
            downloadRepository = repository,
            offlineAssetRepository = offlineAssetRepository,
            subtitleIndexRepository = subtitleIndexRepository,
            studyRepository = studyRepository,
            stagingRoot = dbDirectory.resolve("portable-staging"),
            ownedRecoveryRootProvider = {
                settingsController.settings.value.downloadsDirectory ?: downloadsDirectory
            },
            playbackSessionControl = playbackLauncher,
        )
    } else {
        null
    }
    val recoveryLocationPicker = DesktopRecoveryLocationPicker()
    val studyService = studyRepository?.let { repo ->
        StudyService(
            repository = repo,
            nowEpochMillis = { System.currentTimeMillis() },
            nextId = { java.util.UUID.randomUUID().toString() },
        )
    }
    val studyEditorCoordinator = StudyEditorCoordinator()
    val studyCaptureService = studyService?.let { service ->
        StudyCaptureAndNavigationService(
            studyService = service,
            playbackLauncher = playbackLauncher,
            playbackStore = playbackStore,
            entryProvider = { coordinator.library.value },
            fileAvailability = FileAvailability { File(it).isFile },
            mediaProbe = mediaProbe,
            nowEpochMillis = { System.currentTimeMillis() },
        )
    }
    val studyAbRepeatController = StudyAbRepeatController(playbackLauncher = playbackLauncher, scope = scope)
    val covers = com.openyt.core.application.ClipCoverPublisher(offlineAssetStore, mediaFileInspector)
    val mediaProcessor = FfmpegMediaProcessor(
        { ExecutableLocator().findFfmpeg() },
        clipStore = database?.let { com.openyt.data.SqlDelightClipJobStore(it) },
        previewRoot = dbDirectory.resolve("clip-previews"),
        outputDirectory = { File(settingsController.settings.value.downloadsDirectory ?: downloadsDirectory) },
        onClipReady = covers::publish,
    )
    val mediaPreview = EngineMediaPreview(
        engine,
        playbackLauncher,
        previewsDirectory.absolutePath,
        { java.util.UUID.randomUUID().toString() },
    )
    val subtitlePicker = object : com.openyt.core.application.SubtitlePicker {
        override val canPick: Boolean = true
        override fun pickSubtitle(onPicked: (com.openyt.core.application.PickedSubtitle?) -> Unit) {
            onPicked(DesktopSubtitlePicker.pick())
        }
    }

    coroutineScope {
        launch { settingsController.restore() }
        launch { coordinator.restore() }
        launch { libraryController.restore() }
    }

    DesktopRuntime(
        settingsController = settingsController,
        offlineAssetStore = offlineAssetStore,
        offlineAssetRepository = offlineAssetRepository,
        artworkFetchService = artworkFetchService,
        subtitleImportService = subtitleImportService,
        subtitleIndexRepository = subtitleIndexRepository,
        timedSearchUseCase = timedSearchUseCase,
        previewsDirectory = previewsDirectory,
        profileRepository = profileRepository,
        cookieRepository = cookieRepository,
        coordinator = coordinator,
        libraryController = libraryController,
        libraryArchiveService = libraryArchiveService,
        folderImportService = folderImportService,
        bulkFileService = bulkFileService,
        mediaProbe = mediaProbe,
        mediaTagger = mediaTagger,
        playbackLauncher = playbackLauncher,
        mediaActions = mediaActions,
        downloadsDirectory = downloadsDirectory,
        portableBackup = portableBackup,
        portableArchivePicker = portableArchivePicker,
        subscriptions = subscriptions,
        diagnosticsProvider = diagnosticsProvider,
        updateController = updateController,
        updateInstaller = updateInstaller,
        urlOpener = urlOpener,
        folderPicker = folderPicker,
        recoveryRuntime = recoveryRuntime,
        recoveryLocationPicker = recoveryLocationPicker,
        studyService = studyService,
        studyEditorCoordinator = studyEditorCoordinator,
        studyCaptureService = studyCaptureService,
        studyAbRepeatController = studyAbRepeatController,
        mediaProcessor = mediaProcessor,
        mediaPreview = mediaPreview,
        toolUpdater = com.openyt.desktop.downloader.RuntimeToolUpdate.updater(),
        offlineAssetInspector = mediaFileInspector,
        subtitlePicker = subtitlePicker,
    )
}

private suspend fun runPostStartupTasks(runtime: DesktopRuntime) = withContext(Dispatchers.IO) {
    runtime.subscriptions?.restore()
    runtime.subscriptions?.start()
    cleanStaleTemporaryFiles(
        File(runtime.settingsController.settings.value.downloadsDirectory ?: runtime.downloadsDirectory),
    )
    runtime.portableBackup?.reconcileOnStartup()
    runtime.recoveryRuntime?.relinkExecutor?.reconcileAfterRestart()
    runCatching { BundledTools.directory() }
}

private fun launchApp(
    initialRoute: OpenYtRoute?,
    instance: DesktopInstance,
    deepLinksCheck: kotlinx.coroutines.CompletableDeferred<Boolean>,
    engine: DesktopYtDlpEngine,
    scope: CoroutineScope,
    runtimeTask: Deferred<DesktopRuntime>,
) = application {
    val notifier = remember { DesktopDownloadNotifier() }
    var runtime by remember { mutableStateOf<DesktopRuntime?>(null) }
    var entryLinksSupported by remember { mutableStateOf(false) }
    var sharedUrl by remember { mutableStateOf((initialRoute as? OpenYtRoute.Download)?.url) }
    var pendingEntryRoute by remember { mutableStateOf(initialRoute as? OpenYtRoute.LibraryEntry) }
    var focusSignal by remember { mutableStateOf(0) }
    val systemLanguageTag = remember { java.util.Locale.getDefault().toLanguageTag() }
    val initialStrings = remember(systemLanguageTag) { stringsFor(AppLanguage.SYSTEM, systemLanguageTag) }

    LaunchedEffect(runtimeTask) {
        val built = runtimeTask.await()
        runtime = built
        scope.launch(Dispatchers.IO) { runPostStartupTasks(built) }
    }

    LaunchedEffect(deepLinksCheck) {
        entryLinksSupported = deepLinksCheck.await()
    }

    LaunchedEffect(instance) {
        val debugHandoff = System.getenv("OPENYT_DEBUG_HANDOFF") == "1"
        if (debugHandoff) System.err.println("OpenYT[handoff]: polling started")
        while (true) {
            val routes = withContext(Dispatchers.IO) { instance.takeRoutes() }
            if (routes.isNotEmpty()) {
                if (debugHandoff) System.err.println("OpenYT[handoff]: received ${routes.size} route(s): $routes")
                routes.forEach { route ->
                    when (route) {
                        is OpenYtRoute.Download -> sharedUrl = route.url
                        is OpenYtRoute.LibraryEntry -> pendingEntryRoute = route
                        null -> Unit
                    }
                }
                focusSignal++
            }
            kotlinx.coroutines.delay(300)
        }
    }

    val shutdown: () -> Unit = {
        notifier.close()
        runtime?.studyAbRepeatController?.close()
        runtime?.playbackLauncher?.close()
        engine.close()
        scope.cancel()
        exitApplication()
    }

    Window(
        onCloseRequest = shutdown,
        title = "OpenYT",
        icon = BrandIconPainter(),
        state = rememberWindowState(width = 1100.dp, height = 780.dp),
        onPreviewKeyEvent = { event ->
            if (
                event.type == KeyEventType.KeyDown &&
                event.isCtrlPressed &&
                event.key == Key.Q
            ) {
                shutdown()
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

        var appFocused by remember { mutableStateOf(window.isFocused) }
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(event: java.awt.event.WindowEvent) { appFocused = true }
                override fun windowLostFocus(event: java.awt.event.WindowEvent) { appFocused = false }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        DisposableEffect(window) {
            window.dropTarget = DesktopDropTarget { url ->
                SwingUtilities.invokeLater { sharedUrl = url }
                window.toFront()
            }
            onDispose { window.dropTarget = null }
        }

        LaunchedEffect(focusSignal, window) {
            if (focusSignal > 0) {
                window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                window.toFront()
                window.requestFocus()
            }
        }
        DisposableEffect(window) {
            val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
            val desktop = if (isMac) runCatching { java.awt.Desktop.getDesktop() }.getOrNull() else null
            val supported = desktop?.isSupported(java.awt.Desktop.Action.APP_OPEN_URI) == true
            if (supported && desktop != null) desktop.setOpenURIHandler { event ->
                OpenYtLink.parseRoute(event.uri.toString())?.let { route ->
                    SwingUtilities.invokeLater {
                        when (route) {
                            is OpenYtRoute.Download -> sharedUrl = route.url
                            is OpenYtRoute.LibraryEntry -> pendingEntryRoute = route
                        }
                        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                        window.toFront()
                        window.requestFocus()
                    }
                }
            }
            onDispose { if (supported && desktop != null) desktop.setOpenURIHandler(null) }
        }

        Crossfade(
            targetState = runtime,
            animationSpec = tween(durationMillis = 220),
            label = "desktop-startup-transition",
        ) { activeRuntime ->
            if (activeRuntime == null) {
                CompositionLocalProvider(LocalStrings provides initialStrings) {
                    OpenYtTheme {
                        StartupLoadingScreen()
                    }
                }
            } else {
                OpenYtApp(
                    isAppActive = appFocused,
                    notifier = notifier,
                    toolUpdater = activeRuntime.toolUpdater,
                    systemLanguageTag = systemLanguageTag,
                    engine = engine,
                    coordinator = activeRuntime.coordinator,
                    subscriptions = activeRuntime.subscriptions,
                    settingsController = activeRuntime.settingsController,
                    libraryController = activeRuntime.libraryController,
                    mediaProbe = activeRuntime.mediaProbe,
                    mediaActions = activeRuntime.mediaActions,
                    diagnosticsProvider = activeRuntime.diagnosticsProvider,
                    updateController = activeRuntime.updateController,
                    updateInstaller = activeRuntime.updateInstaller,
                    urlOpener = activeRuntime.urlOpener,
                    downloadsDirectory = activeRuntime.downloadsDirectory,
                    fileAvailability = FileAvailability { File(it).isFile },
                    initialSharedUrl = sharedUrl,
                    onSharedUrlConsumed = { sharedUrl = null },
                    folderPicker = activeRuntime.folderPicker,
                    cookieRepository = activeRuntime.cookieRepository,
                    nowEpochMillis = { System.currentTimeMillis() },
                    mediaTagger = activeRuntime.mediaTagger,
                    libraryArchiveService = activeRuntime.libraryArchiveService,
                    folderImportService = activeRuntime.folderImportService,
                    bulkFileService = activeRuntime.bulkFileService,
                    playbackLauncher = activeRuntime.playbackLauncher,
                    mediaProcessor = activeRuntime.mediaProcessor,
                    mediaPreview = activeRuntime.mediaPreview,
                    profileRepository = activeRuntime.profileRepository,
                    offlineAssetStore = activeRuntime.offlineAssetStore,
                    offlineAssetRepository = activeRuntime.offlineAssetRepository,
                    offlineAssetInspector = activeRuntime.offlineAssetInspector,
                    subtitlePicker = activeRuntime.subtitlePicker,
                    subtitleImportService = activeRuntime.subtitleImportService,
                    artworkFetchService = activeRuntime.artworkFetchService,
                    renderingCacheBytes = { activeRuntime.previewsDirectory.directorySize() },
                    timedSearchUseCase = activeRuntime.timedSearchUseCase,
                    subtitleIndexRepository = activeRuntime.subtitleIndexRepository,
                    studyService = activeRuntime.studyService,
                    studyCaptureService = activeRuntime.studyCaptureService,
                    studyEditorCoordinator = activeRuntime.studyEditorCoordinator,
                    studyAbRepeatController = activeRuntime.studyAbRepeatController,
                    studyExporter = DesktopTextFileExporter,
                    hostLinkCapability = StudyHostLinkCapability(
                        supportsClickableEntryLinks = entryLinksSupported,
                        hostLabel = "desktop",
                    ),
                    initialEntryRoute = pendingEntryRoute,
                    onEntryRouteConsumed = { pendingEntryRoute = null },
                    portableBackupController = activeRuntime.portableBackup?.controller,
                    portableArchivePicker = activeRuntime.portableArchivePicker,
                    libraryRecoveryController = activeRuntime.recoveryRuntime?.controller,
                    recoveryLocationPicker = activeRuntime.recoveryLocationPicker,
                )
            }
        }
    }
}

private fun File.directorySize(): Long =
    if (isDirectory) {
        listFiles()?.sumOf { it.directorySize() } ?: 0L
    } else {
        length()
    }
