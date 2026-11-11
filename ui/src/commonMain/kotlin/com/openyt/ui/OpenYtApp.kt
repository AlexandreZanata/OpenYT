package com.openyt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.openyt.core.application.BulkFileService
import com.openyt.core.application.CookieRepository
import com.openyt.core.application.DiagnosticsProvider
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.FolderImportService
import com.openyt.core.application.FolderPicker
import com.openyt.core.application.LibraryArchiveService
import com.openyt.core.application.LibraryController
import com.openyt.core.application.MediaActions
import com.openyt.core.application.MediaImporter
import com.openyt.core.application.MediaProbe
import com.openyt.core.application.MediaTagger
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.application.MediaProcessor
import com.openyt.core.application.MediaPreview
import com.openyt.core.application.SafDestinationPicker
import com.openyt.core.application.SettingsController
import com.openyt.core.automation.SubscriptionController
import com.openyt.core.application.UpdateController
import com.openyt.core.application.UpdateInstaller
import com.openyt.core.application.UpdateState
import com.openyt.core.application.UrlOpener
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.DownloadWindow
import com.openyt.core.domain.OrganizationPreset
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.ThemeMode
import com.openyt.ui.components.BrandMark
import com.openyt.ui.components.UpdateBanner
import com.openyt.ui.i18n.stringsFor
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.DownloadsScreen
import com.openyt.ui.screens.HomeScreen
import com.openyt.ui.screens.LibraryScreen
import com.openyt.ui.screens.SettingsScreen
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.launch

@Composable
fun OpenYtApp(
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    settingsController: SettingsController,
    libraryController: LibraryController,
    mediaProbe: MediaProbe,
    mediaActions: MediaActions,
    mediaImporter: MediaImporter? = null,
    diagnosticsProvider: DiagnosticsProvider,
    updateController: UpdateController,
    updateInstaller: UpdateInstaller,
    urlOpener: UrlOpener,
    safDestinationPicker: SafDestinationPicker? = null,
    downloadsDirectory: String,
    fileAvailability: FileAvailability,
    initialSharedUrl: String? = null,
    onSharedUrlConsumed: () -> Unit = {},
    folderPicker: FolderPicker? = null,
    cookieRepository: CookieRepository? = null,
    nowEpochMillis: () -> Long = { 0L },
    mediaTagger: MediaTagger? = null,
    libraryArchiveService: LibraryArchiveService? = null,
    folderImportService: FolderImportService? = null,
    bulkFileService: BulkFileService? = null,
    playbackLauncher: PlaybackLauncher? = null,
    systemLanguageTag: String = "en",
    isAppActive: Boolean = true,
    notifier: com.openyt.core.application.DownloadNotifier? = null,
    toolUpdater: com.openyt.core.application.ToolUpdater? = null,
    mediaProcessor: MediaProcessor? = null,
    mediaPreview: MediaPreview? = null,
    subscriptions: SubscriptionController? = null,
) {
    val ready by coordinator.ready.collectAsState()
    val settings by settingsController.settings.collectAsState()
    val strings = stringsFor(settings.language, systemLanguageTag)
    val tasks by coordinator.tasks.collectAsState()
    val notificationTracker = remember { com.openyt.core.application.DownloadNotificationTracker() }
    LaunchedEffect(settings.downloadNotifications) {
        if (settings.downloadNotifications) notifier?.requestPermission()
    }
    LaunchedEffect(tasks, ready) {
        if (!ready) return@LaunchedEffect
        notificationTracker.update(tasks).forEach { task ->
            if (settings.downloadNotifications) notifier?.show(
                task.id, task.media.title,
                if (task.status == com.openyt.core.domain.DownloadStatus.COMPLETED) strings.statusCompleted else strings.statusFailed,
                task.status == com.openyt.core.domain.DownloadStatus.FAILED,
            )
        }
    }
    val effectiveDownloadsDirectory = settings.downloadsDirectory ?: downloadsDirectory

    LaunchedEffect(settings.maxConcurrentDownloads) {
        coordinator.setMaxConcurrentDownloads(settings.maxConcurrentDownloads)
    }
    LaunchedEffect(
        settings.speedLimitBytesPerSecond,
        settings.downloadWindowStartMinutes,
        settings.downloadWindowEndMinutes,
    ) {
        coordinator.setSpeedLimit(settings.speedLimitBytesPerSecond)
        val start = settings.downloadWindowStartMinutes
        val end = settings.downloadWindowEndMinutes
        coordinator.setDownloadWindow(
            if (start != null && end != null && start != end) {
                DownloadWindow(start, end)
            } else {
                null
            },
        )
    }
    LaunchedEffect(Unit) {
        updateController.checkIfNeeded()
    }

    CompositionLocalProvider(LocalStrings provides strings) {
        OpenYtTheme(
            darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            },
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (!ready) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                } else AppShell(
                    engine = engine,
                    coordinator = coordinator,
                    settingsController = settingsController,
                    libraryController = libraryController,
                    mediaProbe = mediaProbe,
                    mediaActions = mediaActions,
                    mediaImporter = mediaImporter,
                    diagnosticsProvider = diagnosticsProvider,
                    updateController = updateController,
                    updateInstaller = updateInstaller,
                    urlOpener = urlOpener,
                    safDestinationPicker = safDestinationPicker,
                    downloadsDirectory = effectiveDownloadsDirectory,
                    organizationPreset = settings.organizationPreset,
                    fileNameTemplate = settings.fileOrganizationTemplate,
                    fileAvailability = fileAvailability,
                    initialSharedUrl = initialSharedUrl,
                    onSharedUrlConsumed = onSharedUrlConsumed,
                    folderPicker = folderPicker,
                    cookieRepository = cookieRepository,
                    nowEpochMillis = nowEpochMillis,
                    mediaTagger = mediaTagger,
                    libraryArchiveService = libraryArchiveService,
                    folderImportService = folderImportService,
                    bulkFileService = bulkFileService,
                    playbackLauncher = playbackLauncher,
                    mediaProcessor = mediaProcessor,
                    mediaPreview = mediaPreview,
                    subscriptions = subscriptions,
                    isAppActive = isAppActive,
                    toolUpdater = toolUpdater,
                )
            }
        }
    }
}


private data class ShellTab(val icon: ImageVector, val label: String)

@Composable
private fun AppShell(
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    settingsController: SettingsController,
    libraryController: LibraryController,
    mediaProbe: MediaProbe,
    mediaActions: MediaActions,
    mediaImporter: MediaImporter? = null,
    diagnosticsProvider: DiagnosticsProvider,
    updateController: UpdateController,
    updateInstaller: UpdateInstaller,
    urlOpener: UrlOpener,
    safDestinationPicker: SafDestinationPicker? = null,
    downloadsDirectory: String,
    organizationPreset: OrganizationPreset,
    fileNameTemplate: String?,
    fileAvailability: FileAvailability,
    initialSharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
    folderPicker: FolderPicker?,
    cookieRepository: CookieRepository?,
    nowEpochMillis: () -> Long,
    mediaTagger: MediaTagger?,
    libraryArchiveService: LibraryArchiveService?,
    folderImportService: FolderImportService?,
    bulkFileService: BulkFileService?,
    playbackLauncher: PlaybackLauncher?,
    mediaProcessor: MediaProcessor?,
    mediaPreview: MediaPreview?,
    subscriptions: SubscriptionController?,
    isAppActive: Boolean,
    toolUpdater: com.openyt.core.application.ToolUpdater?,
) {
    val strings = LocalStrings.current
    val tabs = listOf(
        ShellTab(Icons.Default.Home, strings.tabHome),
        ShellTab(Icons.Default.Download, strings.tabDownloads),
        ShellTab(Icons.Default.VideoLibrary, strings.tabLibrary),
        ShellTab(Icons.Default.Settings, strings.tabSettings),
    )
    var selected by rememberSaveable { mutableStateOf(0) }
    val updateState by updateController.state.collectAsState()
    var dismissedUpdateVersion by remember { mutableStateOf<String?>(null) }
    var installingUpdate by remember { mutableStateOf(false) }
    var updateInstallFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings by settingsController.settings.collectAsState()
    var clipboardUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) selected = 0
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                    return@onPreviewKeyEvent false
                }
                val target = when (event.key) {
                    Key.One -> 0
                    Key.Two -> 1
                    Key.Three -> 2
                    Key.Four -> 3
                    else -> null
                } ?: return@onPreviewKeyEvent false
                selected = target
                true
            },
    ) {
        val wide = maxWidth >= 720.dp
        val content: @Composable (PaddingValues) -> Unit = { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                com.openyt.ui.components.ClipboardSuggestion(settings.clipboardSuggestions, isAppActive) {
                    clipboardUrl = it
                    selected = 0
                }
                val availableUpdate = (updateState as? UpdateState.Available)?.update
                if (availableUpdate != null && dismissedUpdateVersion != availableUpdate.version) {
                    UpdateBanner(
                        update = availableUpdate,
                        installing = installingUpdate,
                        onInstall = {
                            scope.launch {
                                installingUpdate = true
                                updateInstallFailed = !updateInstaller.install(availableUpdate)
                                installingUpdate = false
                            }
                        },
                        onOpenRelease = { urlOpener.open(availableUpdate.releaseUrl) },
                        onDismiss = { dismissedUpdateVersion = availableUpdate.version; updateInstallFailed = false },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (updateInstallFailed) Text(strings.installFailed,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Box(Modifier.fillMaxSize().weight(1f)) {
                when (selected) {
                    0 -> HomeScreen(
                        engine = engine,
                        coordinator = coordinator,
                        downloadsDirectory = downloadsDirectory,
                        onDownloadStarted = { selected = 1 },
                        organizationPreset = organizationPreset,
                        fileNameTemplate = fileNameTemplate,
                        initialSharedUrl = clipboardUrl ?: initialSharedUrl,
                        onSharedUrlConsumed = { clipboardUrl = null; onSharedUrlConsumed() },
                        showBrand = !wide,
                        mediaPreview = mediaPreview,
                    )

                    1 -> DownloadsScreen(
                        coordinator = coordinator,
                        mediaActions = mediaActions,
                        playbackLauncher = playbackLauncher,
                    )
                    2 -> LibraryScreen(
                        coordinator,
                        fileAvailability,
                        libraryController,
                        mediaProbe,
                        mediaActions,
                        mediaImporter,
                        downloadsDirectory = downloadsDirectory,
                        nowEpochMillis = nowEpochMillis,
                        mediaTagger = mediaTagger,
                        archiveService = libraryArchiveService,
                        folderImportService = folderImportService,
                        bulkFileService = bulkFileService,
                        playbackLauncher = playbackLauncher,
                        mediaProcessor = mediaProcessor,
                    )
                    else -> SettingsScreen(
                        toolUpdater = toolUpdater,
                        engine = engine,
                        downloadsDirectory = downloadsDirectory,
                        settingsController = settingsController,
                        coordinator = coordinator,
                        libraryController = libraryController,
                        diagnosticsProvider = diagnosticsProvider,
                        updateController = updateController,
                        updateInstaller = updateInstaller,
                        urlOpener = urlOpener,
                        safDestinationPicker = safDestinationPicker,
                        folderPicker = folderPicker,
                        cookieRepository = cookieRepository,
                        subscriptions = subscriptions,
                    )
                }
                }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(96.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrandMark(vertical = true)
                    NavigationRail {
                        tabs.forEachIndexed { index, tab ->
                            NavigationRailItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) { content(PaddingValues(0.dp)) }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize()) { content(padding) }
            }
        }
    }
}
