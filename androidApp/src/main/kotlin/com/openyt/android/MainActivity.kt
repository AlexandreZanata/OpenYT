package com.openyt.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.openyt.android.player.androidMediaProcessor
import com.openyt.core.application.EngineMediaPreview
import com.openyt.core.application.OpenYtLink
import com.openyt.core.application.OpenYtRoute
import com.openyt.core.application.StudyHostLinkCapability
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.openyt.core.application.FileAvailability
import com.openyt.android.diagnostics.AndroidUrlOpener
import com.openyt.android.importer.AndroidMediaImporter
import com.openyt.android.importer.AndroidSubtitlePicker
import com.openyt.android.player.AndroidPlaybackLauncher
import com.openyt.android.saf.AndroidSafDestinationPicker
import com.openyt.android.saf.AndroidTextFileExporter
import com.openyt.android.share.AndroidMediaActions
import com.openyt.core.application.ShareTextParser
import com.openyt.ui.OpenYtApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var appActive by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val sharedUrl = MutableStateFlow<String?>(null)
    private val pendingEntryRoute = MutableStateFlow<OpenYtRoute.LibraryEntry?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i(
            "OpenYT",
            "onCreate action=${intent?.action} hasText=${intent?.hasExtra(Intent.EXTRA_TEXT)} state=${savedInstanceState != null}",
        )
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }

        val container = (application as OpenYtApplication).container
        val mediaActions = AndroidMediaActions(this)
        val mediaImporter = AndroidMediaImporter(this, container.downloadsDirectory)
        val urlOpener = AndroidUrlOpener(this)
        val safDestinationPicker = AndroidSafDestinationPicker(this)
        val portableArchivePicker = com.openyt.android.saf.AndroidPortableArchivePicker(this)
        val subtitlePicker = AndroidSubtitlePicker(this)
        val studyExporter = AndroidTextFileExporter(this)
        setContent {
            val pendingSharedUrl by sharedUrl.collectAsState()
            val entryRoute by pendingEntryRoute.collectAsState()
            val playback = remember { AndroidPlaybackLauncher(this, container.playbackStore) }
            val studyCaptureService = remember(playback) {
                container.createStudyCaptureAndNavigationService(playback)
            }
            val studyAbRepeatController = remember(playback) {
                container.createStudyAbRepeatController(playback)
            }
            DisposableEffect(studyAbRepeatController) {
                onDispose { studyAbRepeatController.close() }
            }
            OpenYtApp(
                isAppActive = appActive,
                notifier = remember { AndroidDownloadNotifier(this) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } },
                systemLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag(),
                engine = container.engine,
                toolUpdater = container.engine as? com.openyt.core.application.ToolUpdater,
                coordinator = container.coordinator,
                subscriptions = container.subscriptions,
                settingsController = container.settingsController,
                libraryController = container.libraryController,
                mediaProbe = container.mediaProbe,
                mediaActions = mediaActions,
                mediaImporter = mediaImporter,
                diagnosticsProvider = container.diagnosticsProvider,
                updateController = container.updateController,
                updateInstaller = container.updateInstaller,
                urlOpener = urlOpener,
                safDestinationPicker = safDestinationPicker,
                downloadsDirectory = container.downloadsDirectory,
                fileAvailability = FileAvailability { java.io.File(it).isFile },
                cookieRepository = container.cookieRepository,
                nowEpochMillis = { System.currentTimeMillis() },
                mediaTagger = container.mediaTagger,
                libraryArchiveService = container.libraryArchiveService,
                folderImportService = container.folderImportService,
                bulkFileService = container.bulkFileService,
                playbackLauncher = playback,
                mediaProcessor = remember { androidMediaProcessor(applicationContext) },
                mediaPreview = remember { EngineMediaPreview(container.engine, playback, java.io.File(cacheDir, "previews").apply { mkdirs() }.absolutePath, { java.util.UUID.randomUUID().toString() }) },
                initialSharedUrl = pendingSharedUrl,
                onSharedUrlConsumed = { sharedUrl.value = null },
                profileRepository = container.profileRepository,
                offlineAssetStore = container.offlineAssetStore,
                offlineAssetRepository = container.offlineAssetRepository,
                offlineAssetInspector = remember { com.openyt.data.JvmMediaFileInspector() },
                subtitlePicker = subtitlePicker,
                subtitleImportService = container.subtitleImportService,
                artworkFetchService = container.artworkFetchService,
                renderingCacheBytes = container.renderingCacheBytes,
                timedSearchUseCase = container.timedSearchUseCase,
                subtitleIndexRepository = container.subtitleIndexRepository,
                studyService = container.studyService,
                studyCaptureService = studyCaptureService,
                studyEditorCoordinator = container.studyEditorCoordinator,
                studyAbRepeatController = studyAbRepeatController,
                studyExporter = studyExporter,
                hostLinkCapability = StudyHostLinkCapability(
                    supportsClickableEntryLinks = true,
                    hostLabel = "android",
                ),
                initialEntryRoute = entryRoute,
                onEntryRouteConsumed = { pendingEntryRoute.value = null },
                portableBackupController = container.portableBackup?.controller,
                portableArchivePicker = portableArchivePicker,
            )
        }
    }

    override fun onResume() { super.onResume(); appActive = true }
    override fun onPause() { appActive = false; super.onPause() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.i("OpenYT", "onNewIntent action=${intent.action} hasText=${intent.hasExtra(Intent.EXTRA_TEXT)}")
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            when (val route = OpenYtLink.parseRoute(intent.dataString)) {
                is OpenYtRoute.Download -> sharedUrl.value = route.url
                is OpenYtRoute.LibraryEntry -> pendingEntryRoute.value = route
                null -> Unit
            }
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val url = ShareTextParser.firstUrl(text)
        android.util.Log.i(
            "OpenYT",
            "share handled: hasText=${text != null} hasUrl=${url != null}",
        )
        sharedUrl.value = url
    }
}
