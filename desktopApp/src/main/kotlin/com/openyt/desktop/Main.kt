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
import kotlinx.coroutines.launch
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
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightDownloadProfileRepository
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
import com.openyt.data.SqlDelightSettingsRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightSubscriptions
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.search.SqlDelightTimedSearchRepository
import com.openyt.data.RssFeedReader
import com.openyt.core.application.MemoryDownloadProfileRepository
import com.openyt.core.automation.SubscriptionController
import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.ui.i18n.stringsFor
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
import com.openyt.desktop.player.DesktopPlaybackLauncher
import com.openyt.desktop.share.DesktopMediaActions
import com.openyt.ui.OpenYtApp
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
    instance.use { launchApp(initialRoute, it) }
}

private fun launchApp(initialRoute: OpenYtRoute?, instance: DesktopInstance) = application {
    val engine = remember { DesktopYtDlpEngine() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val database = remember {
        runCatching { createOpenYtDatabase(defaultDatabasePath()) }
            .onFailure { System.err.println("OpenYT: database initialization failed (${it::class.simpleName})") }
            .getOrNull()
    }
    val repository = remember(database) { database?.let(::SqlDelightDownloadRepository) }
    val offlineAssetsDirectory = remember {
        File(defaultDatabasePath()).parentFile.resolve("offline-assets").apply { mkdirs() }
    }
    val offlineAssetRepository = remember(database) {
        database?.let(::SqlDelightOfflineAssetRepository)
    }
    val offlineAssetStore = remember(database, offlineAssetRepository) {
        val offlineRepository = offlineAssetRepository
        if (database != null && offlineRepository != null) {
            JvmOfflineAssetStore(
                assetsDirectory = offlineAssetsDirectory,
                repository = offlineRepository,
                operationRepository = SqlDelightAssetOperationRepository(database),
            )
        } else {
            null
        }
    }
    val artworkFetchService = remember(offlineAssetStore, offlineAssetRepository) {
        val store = offlineAssetStore
        val offlineRepository = offlineAssetRepository
        if (store != null && offlineRepository != null) {
            ArtworkFetchService(
                operator = store.operator,
                fetchPort = JvmArtworkFetchPort(),
                assetRepository = offlineRepository,
                stagingPort = store.stagingPort,
            )
        } else {
            null
        }
    }
    val subtitleImportService = remember(offlineAssetStore, offlineAssetRepository) {
        val store = offlineAssetStore
        val offlineRepository = offlineAssetRepository
        if (store != null && offlineRepository != null) {
            SubtitleImportService(
                operator = store.operator,
                assetRepository = offlineRepository,
                stagingPort = store.stagingPort,
                fileInspector = JvmMediaFileInspector(),
            )
        } else {
            null
        }
    }
    val subtitleIndexRepository = remember(database) {
        database?.let(::SqlDelightSubtitleIndexRepository)
    }
    val timedSearchRepository = remember(database) {
        database?.let(::SqlDelightTimedSearchRepository)
    }
    val timedSearchUseCase = remember(timedSearchRepository) {
        timedSearchRepository?.let(::TimedSearchUseCase)
    }
    val previewsDirectory = remember {
        File(System.getProperty("java.io.tmpdir"), "openyt-previews").apply { mkdirs() }
    }
    val profileRepository = remember(database) {
        database?.let(::SqlDelightDownloadProfileRepository) ?: MemoryDownloadProfileRepository()
    }
    val settingsController = remember(database) {
        SettingsController(database?.let(::SqlDelightSettingsRepository), scope)
    }
    val cookieRepository = remember {
        EncryptedCookieRepository(File(defaultDatabasePath()).parentFile.resolve("cookies"))
    }
    val coordinator = remember(engine, offlineAssetStore) {
        DownloadCoordinator(
            engine = engine,
            scope = scope,
            repository = repository,
            cookieRepository = cookieRepository,
            assetStore = offlineAssetStore,
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
    val bulkFileService = remember(repository) {
        BulkFileService(FileBulkFileOperator(), repository)
    }
    val mediaProbe = remember { DesktopMediaProbe() }
    val mediaTagger = remember { DesktopMediaTagger() }
    val playbackStore = remember(database) { database?.let(::SqlDelightPlaybackStore) ?: MemoryPlaybackStore() }
    val playbackLauncher = remember {
        DesktopPlaybackLauncher(playbackStore, {
            stringsFor(settingsController.settings.value.language, java.util.Locale.getDefault().toLanguageTag())
        }, scope)
    }
    val mediaActions = remember { DesktopMediaActions() }
    val downloadsDirectory = remember { defaultDownloadsDirectory() }
    val subscriptions = remember(database) {
        database?.let { SubscriptionController(SqlDelightSubscriptions(it), RssFeedReader(), engine, coordinator,
            { settingsController.settings.value.downloadsDirectory ?: downloadsDirectory },
            { System.currentTimeMillis() }, { java.util.UUID.randomUUID().toString() }, scope) }
    }
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
    val notifier = remember { DesktopDownloadNotifier() }
    val updateInstaller = remember { DesktopUpdateInstaller() }
    val urlOpener = remember { DesktopUrlOpener() }
    val folderPicker = remember { FolderPicker { current -> DesktopFolderPicker.pick(current) } }
    val studyRepository = remember(database) { database?.let(::SqlDelightStudyRepository) }
    val studyService = remember(studyRepository) {
        studyRepository?.let { repo ->
            StudyService(
                repository = repo,
                nowEpochMillis = { System.currentTimeMillis() },
                nextId = { java.util.UUID.randomUUID().toString() },
            )
        }
    }
    val studyEditorCoordinator = remember { StudyEditorCoordinator() }
    val studyCaptureService = remember(studyService, playbackLauncher, playbackStore) {
        studyService?.let { service ->
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
    }
    val studyAbRepeatController = remember(playbackLauncher, scope) {
        StudyAbRepeatController(playbackLauncher = playbackLauncher, scope = scope)
    }
    var entryLinksSupported by remember { mutableStateOf(false) }
    val deepLinksCheck = remember {
        kotlinx.coroutines.CompletableDeferred<Boolean>().also { deferred ->
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val supported = runCatching {
                    DesktopDeepLinks.register()
                    DesktopDeepLinks.isEntryLinkDispatchSupported()
                }.getOrDefault(false)
                deferred.complete(supported)
            }
        }
    }
    var sharedUrl by remember { mutableStateOf((initialRoute as? OpenYtRoute.Download)?.url) }
    var pendingEntryRoute by remember { mutableStateOf(initialRoute as? OpenYtRoute.LibraryEntry) }

    LaunchedEffect(coordinator, settingsController, libraryController) {
        entryLinksSupported = deepLinksCheck.await()
        coordinator.restore()
        settingsController.restore()
        libraryController.restore()
        subscriptions?.restore()
        subscriptions?.start()
        cleanStaleTemporaryFiles(
            File(settingsController.settings.value.downloadsDirectory ?: downloadsDirectory),
        )
    }

    Window(
        onCloseRequest = {
            notifier.close()
            studyAbRepeatController.close()
            playbackLauncher.close()
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
                notifier.close()
                studyAbRepeatController.close()
                playbackLauncher.close()
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

        LaunchedEffect(instance, window) {
            while (true) {
                val routes = kotlinx.coroutines.withContext(Dispatchers.IO) { instance.takeRoutes() }
                routes.forEach { route ->
                    when (route) {
                        is OpenYtRoute.Download -> sharedUrl = route.url
                        is OpenYtRoute.LibraryEntry -> pendingEntryRoute = route
                        null -> Unit
                    }
                    window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                    window.toFront()
                    window.requestFocus()
                }
                kotlinx.coroutines.delay(300)
            }
        }
        DisposableEffect(window) {
            val desktop = runCatching { java.awt.Desktop.getDesktop() }.getOrNull()
            val supported = desktop?.isSupported(java.awt.Desktop.Action.APP_OPEN_URI) == true
            if (supported) desktop?.setOpenURIHandler { event ->
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
            onDispose { if (supported) desktop?.setOpenURIHandler(null) }
        }

        OpenYtApp(
            isAppActive = appFocused,
            notifier = notifier,
            toolUpdater = remember { com.openyt.desktop.downloader.RuntimeToolUpdate.updater() },
            systemLanguageTag = java.util.Locale.getDefault().toLanguageTag(),
            engine = engine,
            coordinator = coordinator,
            subscriptions = subscriptions,
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
            bulkFileService = bulkFileService,
            playbackLauncher = playbackLauncher,
            mediaProcessor = remember { FfmpegMediaProcessor({ ExecutableLocator().findFfmpeg() }) },
            mediaPreview = remember { EngineMediaPreview(engine, playbackLauncher, previewsDirectory.absolutePath, { java.util.UUID.randomUUID().toString() }) },
            profileRepository = profileRepository,
            offlineAssetStore = offlineAssetStore,
            offlineAssetRepository = offlineAssetRepository,
            offlineAssetInspector = remember { JvmMediaFileInspector() },
            subtitlePicker = remember {
                object : com.openyt.core.application.SubtitlePicker {
                    override val canPick: Boolean = true
                    override fun pickSubtitle(onPicked: (com.openyt.core.application.PickedSubtitle?) -> Unit) {
                        onPicked(DesktopSubtitlePicker.pick())
                    }
                }
            },
            subtitleImportService = subtitleImportService,
            artworkFetchService = artworkFetchService,
            renderingCacheBytes = { previewsDirectory.directorySize() },
            timedSearchUseCase = timedSearchUseCase,
            subtitleIndexRepository = subtitleIndexRepository,
            studyService = studyService,
            studyCaptureService = studyCaptureService,
            studyEditorCoordinator = studyEditorCoordinator,
            studyAbRepeatController = studyAbRepeatController,
            studyExporter = DesktopTextFileExporter,
            hostLinkCapability = StudyHostLinkCapability(
                supportsClickableEntryLinks = entryLinksSupported,
                hostLabel = "desktop",
            ),
            initialEntryRoute = pendingEntryRoute,
            onEntryRouteConsumed = { pendingEntryRoute = null },
        )
    }
}

private fun File.directorySize(): Long =
    if (isDirectory) {
        listFiles()?.sumOf { it.directorySize() } ?: 0L
    } else {
        length()
    }
