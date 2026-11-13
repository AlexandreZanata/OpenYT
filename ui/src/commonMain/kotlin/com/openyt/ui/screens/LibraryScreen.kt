package com.openyt.ui.screens

import com.openyt.ui.components.FileActionButtons

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.AudioMetadata
import com.openyt.core.application.BulkFilePlanner
import com.openyt.core.application.BulkFileService
import com.openyt.core.application.BulkPlan
import com.openyt.core.application.LibraryArchiveCodec
import com.openyt.core.application.LibraryArchiveService
import com.openyt.core.application.LibraryImportPolicy
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FolderImportService
import com.openyt.core.application.StorageAnalyzer
import com.openyt.core.application.StorageReport
import com.openyt.core.application.FileAvailability
import com.openyt.core.application.LibraryController
import com.openyt.core.application.LibraryFilter
import com.openyt.core.application.LibraryItem
import com.openyt.core.application.toLibraryFilter
import com.openyt.core.application.LibraryQuery
import com.openyt.core.application.MediaActions
import com.openyt.core.application.MediaImporter
import com.openyt.core.application.MediaProbe
import com.openyt.core.application.MediaTagger
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaTechnicalInfo
import com.openyt.core.domain.ShareableMedia
import com.openyt.ui.components.MediaThumbnail
import com.openyt.ui.components.PortableBackupDialog
import com.openyt.ui.components.PortableBackupDialogTab
import com.openyt.ui.components.StorageDialog
import com.openyt.ui.components.MediaToolsDialog
import com.openyt.core.application.PortableArchivePickerPort
import com.openyt.core.application.PortableBackupUiController
import com.openyt.core.application.MediaProcessor
import com.openyt.core.application.MediaFileInspector
import com.openyt.core.application.OfflineAssetMaintenance
import com.openyt.core.application.OfflineAssetRepository
import com.openyt.core.application.OfflineAssetStore
import com.openyt.core.application.OfflineAssetView
import com.openyt.core.application.OfflineLibraryPresenter
import com.openyt.core.application.OfflineLibraryState
import com.openyt.core.application.OfflineStorageBreakdown
import com.openyt.core.application.SubtitleImportService
import com.openyt.core.application.SubtitlePicker
import com.openyt.core.application.ArtworkFetchService
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.RecoveryAction
import com.openyt.ui.components.OfflineReadinessCard
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.components.ProviderIcon
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.fileNameOf
import com.openyt.ui.util.formatBytes
import com.openyt.ui.util.formatDuration
import kotlinx.coroutines.CancellationException
import com.openyt.core.application.SubtitleIndexRepository
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.core.application.OpenYtRoute
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyCaptureAndNavigationService
import com.openyt.core.application.StudyEditorCoordinator
import com.openyt.core.application.StudyHostLinkCapability
import com.openyt.core.application.StudyService
import com.openyt.core.application.TextFileExporter
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.CollectionStudyProgress
import com.openyt.core.domain.IndexUnavailableReason
import com.openyt.core.domain.StudyRemovalPreview
import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import com.openyt.ui.components.LibraryStudyFilter
import com.openyt.ui.components.StudyRemovalDialog
import com.openyt.ui.components.StudySheetDialog
import com.openyt.ui.components.TimedSearchMatchCard
import com.openyt.ui.components.TimedSearchScopeIndicator
import com.openyt.ui.components.TimedSearchStateView
import com.openyt.ui.i18n.PtBrStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibrarySearchMode {
    METADATA,
    SUBTITLES,
}

private data class StudyDialogRequest(
    val entryId: String,
    val initialLinkedTimestampMillis: Long? = null,
    val openedViaLocalLink: Boolean = false,
)

private data class PendingStudyRemoval(
    val entryIds: List<String>,
    val previews: List<StudyRemovalPreview>,
    val onCommitted: () -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    coordinator: DownloadCoordinator,
    fileAvailability: FileAvailability,
    libraryController: LibraryController,
    mediaProbe: MediaProbe,
    mediaActions: MediaActions,
    mediaImporter: MediaImporter?,
    downloadsDirectory: String,
    modifier: Modifier = Modifier,
    nowEpochMillis: () -> Long = { 0L },
    mediaTagger: MediaTagger? = null,
    archiveService: LibraryArchiveService? = null,
    folderImportService: FolderImportService? = null,
    bulkFileService: BulkFileService? = null,
    playbackLauncher: PlaybackLauncher? = null,
    mediaProcessor: MediaProcessor? = null,
    offlineAssetStore: OfflineAssetStore? = null,
    offlineAssetRepository: OfflineAssetRepository? = null,
    offlineAssetInspector: MediaFileInspector? = null,
    subtitlePicker: SubtitlePicker? = null,
    subtitleImportService: SubtitleImportService? = null,
    artworkFetchService: ArtworkFetchService? = null,
    renderingCacheBytes: () -> Long = { 0L },
    timedSearchUseCase: TimedSearchUseCase? = null,
    subtitleIndexRepository: SubtitleIndexRepository? = null,
    studyService: StudyService? = null,
    studyCaptureService: StudyCaptureAndNavigationService? = null,
    studyEditorCoordinator: StudyEditorCoordinator? = null,
    studyAbRepeatController: StudyAbRepeatController? = null,
    studyExporter: TextFileExporter? = null,
    hostLinkCapability: StudyHostLinkCapability = StudyHostLinkCapability(false),
    pendingEntryRoute: OpenYtRoute.LibraryEntry? = null,
    onEntryRouteConsumed: () -> Unit = {},
    portableBackupController: PortableBackupUiController? = null,
    portableArchivePicker: PortableArchivePickerPort? = null,
    onPortableRestored: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val playbackScope = rememberCoroutineScope()
    var resumeUnavailable by remember { mutableStateOf(false) }
    val entries by coordinator.library.collectAsState()
    val collections by libraryController.collections.collectAsState()
    val membership by libraryController.membership.collectAsState()
    val smartCollections by libraryController.smartCollections.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var searchMode by rememberSaveable { mutableStateOf(LibrarySearchMode.METADATA) }
    var timedSearchResult by remember { mutableStateOf<TimedSearchResult?>(null) }
    var timedSearchLoading by remember { mutableStateOf(false) }
    var timedSearchLanguage by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<MediaKind?>(null) }
    var missingOnly by remember { mutableStateOf(false) }
    var duplicatesOnly by remember { mutableStateOf(false) }
    var checkToken by remember { mutableStateOf(0) }
    var missingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detail by remember { mutableStateOf<LibraryItem?>(null) }
    var processingEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var sortByTitle by remember { mutableStateOf(false) }
    var confirmRemoveDuplicates by remember { mutableStateOf(false) }
    var selectedCollectionId by remember { mutableStateOf<String?>(null) }
    var showCreateCollection by remember { mutableStateOf(false) }
    var newCollectionName by remember { mutableStateOf("") }
    var assigningEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var confirmDeleteCollection by remember { mutableStateOf(false) }
    var selectedSmartId by remember { mutableStateOf<String?>(null) }
    var showCreateSmart by remember { mutableStateOf(false) }
    var confirmDeleteSmart by remember { mutableStateOf(false) }
    var smartName by remember { mutableStateOf("") }
    var smartQuery by remember { mutableStateOf("") }
    var smartTag by remember { mutableStateOf("") }
    var smartMinMb by remember { mutableStateOf("") }
    var smartMaxMb by remember { mutableStateOf("") }
    var smartKind by remember { mutableStateOf<MediaKind?>(null) }
    var smartDays by remember { mutableStateOf(0) }
    var showExport by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf("") }
    var includePaths by remember { mutableStateOf(true) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importPolicy by remember { mutableStateOf(LibraryImportPolicy.MERGE) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var showWatchImport by remember { mutableStateOf(false) }
    var watchPath by remember { mutableStateOf(downloadsDirectory) }
    var watchMessage by remember { mutableStateOf<String?>(null) }
    var watching by remember { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var portableBackupTab by remember { mutableStateOf<PortableBackupDialogTab?>(null) }
    var storageReport by remember { mutableStateOf<StorageReport?>(null) }
    var storageLoading by remember { mutableStateOf(false) }
    var storageFailed by remember { mutableStateOf(false) }
    var offlineState by remember { mutableStateOf<OfflineLibraryState?>(null) }
    var artworkByEntry by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var offlineStorage by remember { mutableStateOf<OfflineStorageBreakdown?>(null) }
    var offlineRefresh by remember { mutableStateOf(0) }
    var subtitleMessage by remember { mutableStateOf<String?>(null) }
    val offlineMaintenance = remember(offlineAssetRepository, offlineAssetStore) {
        val repository = offlineAssetRepository
        val store = offlineAssetStore
        if (repository != null && store != null) OfflineAssetMaintenance(repository, store) else null
    }
    var showBulkRename by remember { mutableStateOf(false) }
    var bulkRenamePattern by remember { mutableStateOf("{title}") }
    var showBulkMove by remember { mutableStateOf(false) }
    var bulkMoveDestination by remember { mutableStateOf("") }
    var bulkMessage by remember { mutableStateOf<String?>(null) }
    var bulkApplying by remember { mutableStateOf(false) }
    var bulkSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    val clipboard = LocalClipboardManager.current
    val archiveScope = rememberCoroutineScope()
    val resolvedLanguage = if (strings === PtBrStrings) AppLanguage.PT_BR else AppLanguage.EN
    val resolvedEditorCoordinator = studyEditorCoordinator ?: remember(studyService) { StudyEditorCoordinator() }
    var studyRefreshToken by remember { mutableStateOf(0) }
    var studiedEntryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var collectionProgress by remember { mutableStateOf<Map<String, CollectionStudyProgress>>(emptyMap()) }
    var studyFilter by remember { mutableStateOf(LibraryStudyFilter.ALL) }
    var studyRequest by remember { mutableStateOf<StudyDialogRequest?>(null) }
    var pendingRemoval by remember { mutableStateOf<PendingStudyRemoval?>(null) }
    var missingLinkNotice by remember { mutableStateOf<String?>(null) }
    val coordinatorReady by coordinator.ready.collectAsState()

    val activeCollectionId = selectedCollectionId?.takeIf { id -> collections.any { it.id == id } }
    val activeSmart = smartCollections.firstOrNull { it.id == selectedSmartId }
    val collectionEntries = when {
        activeSmart != null -> entries
        activeCollectionId != null -> entries.filter { it.id in membership[activeCollectionId].orEmpty() }
        else -> entries
    }

    LaunchedEffect(studyService, entries, membership, studyRefreshToken) {
        if (studyService == null) {
            studiedEntryIds = emptySet()
            collectionProgress = emptyMap()
            return@LaunchedEffect
        }
        val (ids, progress) = withContext(Dispatchers.Default) {
            studyService.loadStudiedEntryIds() to studyService.allCollectionProgress()
        }
        studiedEntryIds = ids
        collectionProgress = progress
    }

    LaunchedEffect(pendingEntryRoute, entries, coordinatorReady) {
        val route = pendingEntryRoute ?: return@LaunchedEffect
        val target = entries.firstOrNull { it.id == route.entryId }
        if (target != null) {
            query = ""
            kind = null
            missingOnly = false
            duplicatesOnly = false
            selectedCollectionId = null
            selectedSmartId = null
            studyFilter = LibraryStudyFilter.ALL
            studyRequest = StudyDialogRequest(
                entryId = target.id,
                initialLinkedTimestampMillis = route.timestampMillis,
                openedViaLocalLink = true,
            )
            missingLinkNotice = null
            onEntryRouteConsumed()
        } else if (coordinatorReady) {
            missingLinkNotice = route.entryId
            onEntryRouteConsumed()
        }
    }

    fun requestRemoval(entryIds: Collection<String>, onCommitted: () -> Unit = {}) {
        val idList = entryIds.distinct()
        if (idList.isEmpty()) return
        val service = studyService
        if (service == null) {
            coordinator.removeFromLibrary(entryIds)
            onCommitted()
            return
        }
        playbackScope.launch {
            val previews = withContext(Dispatchers.Default) {
                entryIds.distinct().map { id -> service.previewEntryRemoval(id) }
            }
            if (previews.any { it.requiresConfirmationPrompt }) {
                pendingRemoval = PendingStudyRemoval(
                    entryIds = entryIds.distinct(),
                    previews = previews,
                    onCommitted = onCommitted,
                )
            } else {
                coordinator.removeFromLibrary(entryIds)
                onCommitted()
                studyRefreshToken++
            }
        }
    }

    LaunchedEffect(entries, checkToken) {
        missingIds = withContext(Dispatchers.Default) {
            entries.filterNot { fileAvailability.exists(it.filePath) }.map { it.id }.toSet()
        }
    }

    LaunchedEffect(query, searchMode, activeCollectionId, timedSearchLanguage, timedSearchUseCase) {
        if (searchMode != LibrarySearchMode.SUBTITLES || timedSearchUseCase == null) {
            timedSearchResult = null
            timedSearchLoading = false
            return@LaunchedEffect
        }
        if (query.isBlank()) {
            timedSearchResult = null
            timedSearchLoading = false
            return@LaunchedEffect
        }
        timedSearchLoading = true
        try {
            kotlinx.coroutines.delay(200)
            val scope = when {
                activeCollectionId != null -> TimedSearchScope.Collection(activeCollectionId)
                else -> TimedSearchScope.Library
            }
            val searchFilter = TimedSearchFilter(language = timedSearchLanguage)
            val res = timedSearchUseCase.execute(query.trim(), scope, searchFilter)
            timedSearchResult = res
        } catch (e: CancellationException) {
            // Cancelled by superseded query
        } catch (t: Throwable) {
            timedSearchResult = TimedSearchResult.IndexUnavailable(
                reason = IndexUnavailableReason.INDEX_FAILED,
                message = t.message ?: "Search failed",
            )
        } finally {
            timedSearchLoading = false
        }
    }

    LaunchedEffect(showStorage, entries, checkToken, downloadsDirectory) {
        if (!showStorage) return@LaunchedEffect
        storageLoading = true
        storageFailed = false
        storageReport = null
        try {
            storageReport = withContext(Dispatchers.Default) {
                val missing = entries.filterNot { fileAvailability.exists(it.filePath) }
                    .map { it.id }.toSet()
                val known = folderImportService?.scan(downloadsDirectory).orEmpty()
                StorageAnalyzer.analyze(entries, missing, known)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            storageFailed = true
        } finally {
            storageLoading = false
        }
    }

    LaunchedEffect(entries, offlineAssetRepository, offlineAssetStore, offlineRefresh) {
        val repository = offlineAssetRepository
        val store = offlineAssetStore
        if (repository == null || store == null) {
            artworkByEntry = emptyMap()
            offlineStorage = null
            return@LaunchedEffect
        }
        val assets = withContext(Dispatchers.Default) { repository.loadAllAssets() }
        artworkByEntry = assets
            .filter { it.kind == AssetKind.ARTWORK && it.isVerified }
            .associate { it.entryId to store.resolveAssetPath(it.relativeLocator) }
        offlineStorage = OfflineStorageBreakdown(
            originalMediaBytes = entries.sumOf { it.sizeBytes ?: 0L },
            durableAssetBytes = assets.sumOf { it.byteCount },
            renderingCacheBytes = renderingCacheBytes(),
        )
    }

    LaunchedEffect(detail?.entry?.id, entries, offlineAssetRepository, offlineRefresh) {
        val entry = detail?.entry
        val repository = offlineAssetRepository
        val store = offlineAssetStore
        val inspector = offlineAssetInspector
        if (entry == null || repository == null || store == null || inspector == null) {
            offlineState = null
            return@LaunchedEffect
        }
        offlineState = withContext(Dispatchers.Default) {
            val assets = repository.loadAssetsForEntry(entry.id)
            OfflineLibraryPresenter.present(
                entry = entry,
                assets = assets,
                remoteArtworkUrl = entry.thumbnailUrl,
                renderingCacheBytes = renderingCacheBytes(),
                fileInspector = inspector,
                resolveAssetPath = { store.resolveAssetPath(it) },
            )
        }
    }

    val filter = activeSmart?.toLibraryFilter()
        ?: LibraryFilter(query, kind, missingOnly, duplicatesOnly)
    val items = remember(collectionEntries, missingIds, filter, activeSmart) {
        LibraryQuery.apply(
            entries = collectionEntries,
            missingIds = missingIds,
            filter = filter,
            nowEpochMillis = nowEpochMillis(),
        )
    }
    val studyFilteredItems = remember(items, studyFilter, studiedEntryIds, studyService) {
        if (studyService == null || studyFilter == LibraryStudyFilter.ALL) {
            items
        } else if (studyFilter == LibraryStudyFilter.STUDIED) {
            items.filter { it.entry.id in studiedEntryIds }
        } else {
            items.filter { it.entry.id !in studiedEntryIds }
        }
    }
    val sortedItems = remember(studyFilteredItems, sortByTitle) {
        if (sortByTitle) studyFilteredItems.sortedBy { it.entry.title.lowercase() } else studyFilteredItems
    }
    val duplicateRemovalIds = remember(collectionEntries) {
        LibraryQuery.duplicateRemovalIds(collectionEntries)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        val visibleIds = sortedItems.map { it.entry.id }.toSet()
        val activeSelection = selected.intersect(visibleIds)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (searchMode == LibrarySearchMode.SUBTITLES) 420.dp else 480.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.fillMaxSize().testTag("library-grid"),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "library-header-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                strings.libraryTitle,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )

            if (resumeUnavailable) Text(strings.features[FeatureText.NO_PLAYBACK_SESSION], style = MaterialTheme.typography.bodySmall)
            missingLinkNotice?.let { missingId ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        strings.study.deepLinkEntryMissing(missingId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).testTag("library-missing-link-notice"),
                    )
                    TextButton(onClick = { missingLinkNotice = null }) {
                        Text(strings.closeAction)
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (playbackLauncher?.canPlay == true) {
                    TextButton(onClick = { playbackScope.launch { resumeUnavailable = !playbackLauncher.resumeLastSession() } }) {
                        Text(strings.features[FeatureText.RESUME_PLAYBACK])
                    }
                }
                if (archiveService != null) {
                    TextButton(
                        onClick = {
                            exportJson = ""
                            showExport = true
                        },
                    ) {
                        Text(strings.exportLibrary)
                    }
                    TextButton(
                        onClick = {
                            importText = ""
                            importMessage = null
                            showImport = true
                        },
                    ) {
                        Text(strings.importLibrary)
                    }
                }
                if (folderImportService != null) {
                    TextButton(
                        onClick = {
                            watchPath = downloadsDirectory
                            watchMessage = null
                            showWatchImport = true
                        },
                    ) {
                        Text(strings.watchFolderImport)
                    }
                }
                TextButton(
                    onClick = {
                        storageLoading = true
                        showStorage = true
                    },
                ) {
                    Text(strings.storagePanel)
                }
                if (portableBackupController != null) {
                    TextButton(
                        onClick = { portableBackupTab = PortableBackupDialogTab.EXPORT },
                    ) {
                        Text(strings.portable.actionTitle)
                    }
                }
                if (mediaImporter?.canImport == true) {
                    TextButton(
                        onClick = {
                            mediaImporter.pickAndImport { imported ->
                                coordinator.addLibraryEntries(imported)
                            }
                        },
                    ) {
                        Text(strings.importAction)
                    }
                }
                TextButton(onClick = { checkToken++ }) { Text(strings.verifyFiles) }
                if (duplicateRemovalIds.isNotEmpty()) {
                    TextButton(onClick = { confirmRemoveDuplicates = true }) {
                        Text(strings.removeDuplicates)
                    }
                }
                if (wide && activeSelection.isNotEmpty()) {
                    if (mediaActions.canShare) {
                        TextButton(
                            onClick = {
                                mediaActions.share(
                                    entries.filter { it.id in activeSelection }
                                        .map { it.toShareable() },
                                    strings.shareChooserTitle,
                                )
                            },
                        ) {
                            Text(strings.shareAction)
                        }
                    }
                    TextButton(
                        onClick = {
                            val selectedIds = activeSelection.toList()
                            requestRemoval(selectedIds) { selected = emptySet() }
                        },
                    ) {
                        Text(strings.removeSelected)
                    }
                    if (bulkFileService != null) {
                        TextButton(
                            onClick = {
                                bulkRenamePattern = "{title}"
                                bulkMessage = null
                                bulkSelection = activeSelection
                                showBulkRename = true
                            },
                        ) {
                            Text(strings.bulkRename)
                        }
                        TextButton(
                            onClick = {
                                bulkMoveDestination = downloadsDirectory
                                bulkMessage = null
                                bulkSelection = activeSelection
                                showBulkMove = true
                            },
                        ) {
                            Text(strings.bulkMove)
                        }
                    }
                }
                if (activeCollectionId != null) {
                    TextButton(onClick = { confirmDeleteCollection = true }) {
                        Text(strings.deleteCollection)
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (searchMode == LibrarySearchMode.SUBTITLES) strings.timedSearch.searchModeSubtitles else strings.searchLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("library-search-field"),
            )

            if (timedSearchUseCase != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = searchMode == LibrarySearchMode.METADATA,
                        onClick = { searchMode = LibrarySearchMode.METADATA },
                        label = { Text(strings.timedSearch.searchModeMetadata) },
                        modifier = Modifier.testTag("search-mode-metadata"),
                    )
                    FilterChip(
                        selected = searchMode == LibrarySearchMode.SUBTITLES,
                        onClick = { searchMode = LibrarySearchMode.SUBTITLES },
                        label = { Text(strings.timedSearch.searchModeSubtitles) },
                        modifier = Modifier.testTag("search-mode-subtitles"),
                    )
                }
            }

            if (searchMode != LibrarySearchMode.SUBTITLES) {
                Text(
                    strings.collectionsLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = activeCollectionId == null && activeSmart == null,
                        onClick = {
                            selectedCollectionId = null
                            selectedSmartId = null
                        },
                        label = { Text(strings.allCollections) },
                    )
                    collections.forEach { collection ->
                        FilterChip(
                            selected = activeCollectionId == collection.id && activeSmart == null,
                            onClick = {
                                selectedSmartId = null
                                selectedCollectionId = if (activeCollectionId == collection.id) {
                                    null
                                } else {
                                    collection.id
                                }
                            },
                            label = { Text(collection.name) },
                        )
                    }
                    TextButton(onClick = { showCreateCollection = true }) {
                        Text(strings.newCollection)
                    }
                }

                Text(
                    strings.smartCollectionsLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    smartCollections.forEach { smart ->
                        FilterChip(
                            selected = selectedSmartId == smart.id,
                            onClick = {
                                selectedCollectionId = null
                                selectedSmartId = if (selectedSmartId == smart.id) null else smart.id
                            },
                            label = { Text(smart.name) },
                        )
                    }
                    TextButton(onClick = { showCreateSmart = true }) {
                        Text(strings.newSmartCollection)
                    }
                    if (activeSmart != null) {
                        TextButton(onClick = { confirmDeleteSmart = true }) {
                            Text(strings.deleteSmartCollection)
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filter.isDefault,
                        onClick = {
                            kind = null
                            missingOnly = false
                            duplicatesOnly = false
                            selectedSmartId = null
                        },
                        label = { Text(strings.filterAll) },
                    )
                    FilterChip(
                        selected = kind == MediaKind.VIDEO,
                        onClick = { kind = if (kind == MediaKind.VIDEO) null else MediaKind.VIDEO },
                        label = { Text(strings.kindVideo) },
                    )
                    FilterChip(
                        selected = kind == MediaKind.AUDIO,
                        onClick = { kind = if (kind == MediaKind.AUDIO) null else MediaKind.AUDIO },
                        label = { Text(strings.kindAudio) },
                    )
                    FilterChip(
                        selected = missingOnly,
                        onClick = { missingOnly = !missingOnly },
                        label = { Text(strings.filterMissing) },
                    )
                    FilterChip(
                        selected = duplicatesOnly,
                        onClick = { duplicatesOnly = !duplicatesOnly },
                        label = { Text(strings.filterDuplicates) },
                    )
                    FilterChip(
                        selected = sortByTitle,
                        onClick = { sortByTitle = !sortByTitle },
                        label = { Text(strings.sortTitle) },
                    )
                    if (studyService != null) {
                        FilterChip(
                            selected = studyFilter == LibraryStudyFilter.ALL,
                            onClick = { studyFilter = LibraryStudyFilter.ALL },
                            label = { Text(strings.study.studyFilterAll) },
                            modifier = Modifier.testTag("library-study-filter-all"),
                        )
                        FilterChip(
                            selected = studyFilter == LibraryStudyFilter.STUDIED,
                            onClick = {
                                studyFilter = if (studyFilter == LibraryStudyFilter.STUDIED) {
                                    LibraryStudyFilter.ALL
                                } else {
                                    LibraryStudyFilter.STUDIED
                                }
                            },
                            label = { Text(strings.study.studyFilterStudied) },
                            modifier = Modifier.testTag("library-study-filter-studied"),
                        )
                        FilterChip(
                            selected = studyFilter == LibraryStudyFilter.UNSTUDIED,
                            onClick = {
                                studyFilter = if (studyFilter == LibraryStudyFilter.UNSTUDIED) {
                                    LibraryStudyFilter.ALL
                                } else {
                                    LibraryStudyFilter.UNSTUDIED
                                }
                            },
                            label = { Text(strings.study.studyFilterUnstudied) },
                            modifier = Modifier.testTag("library-study-filter-unstudied"),
                        )
                    }
                }

                if (studyService != null && entries.isNotEmpty()) {
                    val activeColProgress = activeCollectionId?.let { colId -> collectionProgress[colId] }
                    val progressStudied = activeColProgress?.studiedCount ?: entries.count { it.id in studiedEntryIds }
                    val progressTotal = activeColProgress?.totalMemberCount ?: entries.size
                    Text(
                        strings.study.studyProgressLabel(progressStudied, progressTotal),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("library-study-progress"),
                    )
                }
            }
                }
            }

            if (searchMode == LibrarySearchMode.SUBTITLES) {
                val indexStatus = (timedSearchResult as? TimedSearchResult.Success)?.indexStatus
                val activeCollection = collections.firstOrNull { it.id == activeCollectionId }
                item(span = { GridItemSpan(maxLineSpan) }, key = "timed-search-scope") {
                    TimedSearchScopeIndicator(
                        scope = when {
                            activeCollectionId != null -> TimedSearchScope.Collection(activeCollectionId)
                            else -> TimedSearchScope.Library
                        },
                        indexStatus = indexStatus,
                        selectedLanguage = timedSearchLanguage,
                        availableLanguages = listOf("pt", "en"),
                        onSelectLanguage = { timedSearchLanguage = it },
                        collectionName = activeCollection?.name,
                    )
                }

                val successResult = timedSearchResult as? TimedSearchResult.Success
                if (successResult != null && successResult.items.isNotEmpty()) {
                    items(successResult.items, key = { it.matchId }) { match ->
                        TimedSearchMatchCard(
                            match = match,
                            onPlay = {
                                val entry = entries.firstOrNull { it.id == match.entryId }
                                if (entry != null && playbackLauncher?.canPlay == true) {
                                    val media = PlayableMedia(
                                        path = entry.filePath,
                                        title = entry.title,
                                        kind = entry.kind,
                                        entryId = entry.id,
                                    )
                                    val targetPosition = (match.startMs + match.timebaseOffsetMs).coerceAtLeast(0L)
                                    playbackLauncher.playAt(
                                        items = listOf(media),
                                        startIndex = 0,
                                        startPositionMillis = targetPosition,
                                    )
                                }
                            },
                        )
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "timed-search-state") {
                        TimedSearchStateView(
                            result = timedSearchResult,
                            isLoading = timedSearchLoading,
                            onImportSubtitle = if (subtitlePicker?.canPick == true) {
                                {
                                    subtitlePicker.pickSubtitle { picked ->
                                        if (picked != null) {
                                            // Picked subtitle ready for import
                                        }
                                    }
                                }
                            } else null,
                            onReindex = {
                                // Reindex trigger
                            },
                        )
                    }
                }
            } else {
                when {
                    entries.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "library-empty") {
                        Text(
                            strings.libraryEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    sortedItems.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "library-no-results") {
                        Text(
                            strings.noResults,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        itemsIndexed(sortedItems, key = { _, item -> item.entry.id }) { index, item ->
                            LibraryCard(
                                item = item,
                                localArtworkPath = artworkByEntry[item.entry.id],
                                studied = item.entry.id in studiedEntryIds,
                                wide = false,
                                selectable = wide,
                                selected = item.entry.id in activeSelection,
                                onSelect = { checked ->
                                    selected = if (checked) {
                                        selected + item.entry.id
                                    } else {
                                        selected - item.entry.id
                                    }
                                },
                                onDetails = { detail = item },
                                onStudy = if (studyService != null) {
                                    {
                                        studyRequest = StudyDialogRequest(entryId = item.entry.id)
                                    }
                                } else null,
                                onProcess = if (mediaProcessor != null && !item.missing && item.entry.kind != MediaKind.SUBTITLE) { { processingEntry = item.entry } } else null,
                                onCollections = { assigningEntry = item.entry },
                                onRemove = { requestRemoval(listOf(item.entry.id)) },
                                onPlay = playbackLauncher
                                    ?.takeIf { it.canPlay && !item.missing && item.entry.kind != MediaKind.SUBTITLE }
                                    ?.let { launcher ->
                                        {
                                            launcher.play(
                                                items = sortedItems.filter { !it.missing && it.entry.kind != MediaKind.SUBTITLE }.map { playable ->
                                                    PlayableMedia(
                                                        path = playable.entry.filePath,
                                                        title = playable.entry.title,
                                                        kind = playable.entry.kind,
                                                        entryId = playable.entry.id,
                                                    )
                                                },
                                                startIndex = sortedItems.filter { !it.missing && it.entry.kind != MediaKind.SUBTITLE }.indexOfFirst { it.entry.id == item.entry.id },
                                            )
                                        }
                                    },
                                mediaActions = mediaActions,
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "library-footer-hint") {
                            Text(
                                strings.libraryFileKeptHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { item ->
        val current = entries.firstOrNull { it.id == item.entry.id }
        val resolved = if (current != null) item.copy(entry = current) else item
        LibraryDetailsDialog(
            item = resolved,
            mediaProbe = mediaProbe,
            mediaTagger = mediaTagger,
            onClose = {
                detail = null
                subtitleMessage = null
            },
            onAddTag = { coordinator.addLibraryTag(item.entry.id, it) },
            onRemoveTag = { coordinator.removeLibraryTag(item.entry.id, it) },
            onSaveMetadata = { title, author ->
                coordinator.updateLibraryMetadata(item.entry.id, title, author)
            },
            offlineState = offlineState,
            onTogglePin = { asset, pinned ->
                val maintenance = offlineMaintenance ?: return@LibraryDetailsDialog
                archiveScope.launch {
                    maintenance.setPinned(resolved.entry.id, asset.id, pinned)
                    offlineRefresh++
                }
            },
            onOfflineCleanup = { ids ->
                val maintenance = offlineMaintenance ?: return@LibraryDetailsDialog
                archiveScope.launch {
                    maintenance.cleanup(resolved.entry.id, ids)
                    offlineRefresh++
                }
            },
            onOfflineAction = { action ->
                when (action) {
                    RecoveryAction.IMPORT_SUBTITLE -> {
                        val picker = subtitlePicker
                        val service = subtitleImportService
                        val revision = resolved.entry.mediaRevision
                        if (picker != null && service != null && revision != null) {
                            picker.pickSubtitle { picked ->
                                if (picked == null) return@pickSubtitle
                                archiveScope.launch {
                                    val result = service.importSubtitleContent(
                                        entryId = resolved.entry.id,
                                        bytes = picked.bytes,
                                        filenameHint = picked.filename,
                                        mediaRevision = revision,
                                    )
                                    subtitleMessage = if (result.isSuccess) null else strings.offline.importSubtitleFailed
                                    offlineRefresh++
                                }
                            }
                        }
                    }

                    RecoveryAction.FETCH_ARTWORK -> {
                        val service = artworkFetchService
                        val url = resolved.entry.thumbnailUrl
                        val revision = resolved.entry.mediaRevision
                        if (service != null && !url.isNullOrBlank() && revision != null) {
                            archiveScope.launch {
                                service.fetchArtwork(resolved.entry.id, url, revision)
                                offlineRefresh++
                            }
                        }
                    }

                    else -> offlineRefresh++
                }
            },
            onRetryReadiness = { offlineRefresh++ },
            canImportSubtitle = subtitlePicker?.canPick == true &&
                subtitleImportService != null &&
                resolved.entry.mediaRevision != null,
            onImportSubtitle = {
                val picker = subtitlePicker
                val service = subtitleImportService
                val revision = resolved.entry.mediaRevision
                if (picker != null && service != null && revision != null) {
                    picker.pickSubtitle { picked ->
                        if (picked == null) return@pickSubtitle
                        archiveScope.launch {
                            val result = service.importSubtitleContent(
                                entryId = resolved.entry.id,
                                bytes = picked.bytes,
                                filenameHint = picked.filename,
                                mediaRevision = revision,
                            )
                            subtitleMessage = if (result.isSuccess) null else strings.offline.importSubtitleFailed
                            offlineRefresh++
                        }
                    }
                }
            },
            subtitleMessage = subtitleMessage,
        )
    }

    if (showCreateCollection) {
        AlertDialog(
            onDismissRequest = {
                showCreateCollection = false
                newCollectionName = ""
            },
            title = { Text(strings.newCollection) },
            text = {
                OutlinedTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    label = { Text(strings.collectionName) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newCollectionName.isNotBlank(),
                    onClick = {
                        val id = libraryController.createCollection(newCollectionName)
                        if (id != null) selectedCollectionId = id
                        newCollectionName = ""
                        showCreateCollection = false
                    },
                ) {
                    Text(strings.createAction)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        newCollectionName = ""
                        showCreateCollection = false
                    },
                ) {
                    Text(strings.cancelAction)
                }
            },
        )
    }

    if (showCreateSmart) {
        AlertDialog(
            onDismissRequest = { showCreateSmart = false },
            title = { Text(strings.newSmartCollection) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = smartName,
                        onValueChange = { smartName = it },
                        label = { Text(strings.smartNameLabel) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = smartQuery,
                        onValueChange = { smartQuery = it },
                        label = { Text(strings.smartQueryLabel) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = smartTag,
                        onValueChange = { smartTag = it },
                        label = { Text(strings.smartTagLabel) },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = smartMinMb,
                            onValueChange = { smartMinMb = it.filter(Char::isDigit) },
                            label = { Text(strings.smartMinSizeLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = smartMaxMb,
                            onValueChange = { smartMaxMb = it.filter(Char::isDigit) },
                            label = { Text(strings.smartMaxSizeLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        strings.smartCreatedLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            0 to strings.smartAny,
                            7 to strings.smartDays7,
                            30 to strings.smartDays30,
                            365 to strings.smartYear,
                        ).forEach { (days, label) ->
                            FilterChip(
                                selected = smartDays == days,
                                onClick = { smartDays = days },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = smartKind == null,
                            onClick = { smartKind = null },
                            label = { Text(strings.smartAny) },
                        )
                        FilterChip(
                            selected = smartKind == MediaKind.VIDEO,
                            onClick = { smartKind = MediaKind.VIDEO },
                            label = { Text(strings.kindVideo) },
                        )
                        FilterChip(
                            selected = smartKind == MediaKind.AUDIO,
                            onClick = { smartKind = MediaKind.AUDIO },
                            label = { Text(strings.kindAudio) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = smartName.isNotBlank(),
                    onClick = {
                        val created = libraryController.createSmartCollection(
                            name = smartName,
                            query = smartQuery,
                            kind = smartKind,
                            tag = smartTag,
                            minSizeBytes = smartMinMb.toLongOrNull()?.times(1024L * 1024L),
                            maxSizeBytes = smartMaxMb.toLongOrNull()?.times(1024L * 1024L),
                            createdWithinDays = smartDays.takeIf { it > 0 },
                        )
                        if (created != null) {
                            selectedCollectionId = null
                            selectedSmartId = created.id
                        }
                        smartName = ""
                        smartQuery = ""
                        smartTag = ""
                        smartMinMb = ""
                        smartMaxMb = ""
                        smartKind = null
                        smartDays = 0
                        showCreateSmart = false
                    },
                ) {
                    Text(strings.createAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateSmart = false }) {
                    Text(strings.cancelAction)
                }
            },
        )
    }

    if (confirmDeleteSmart && activeSmart != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSmart = false },
            title = { Text(strings.deleteSmartCollection) },
            text = { Text(strings.deleteCollectionHint) },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryController.deleteSmartCollection(activeSmart.id)
                        selectedSmartId = null
                        confirmDeleteSmart = false
                    },
                ) {
                    Text(strings.deleteCollection)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSmart = false }) {
                    Text(strings.cancelAction)
                }
            },
        )
    }

    if (showExport) {
        LaunchedEffect(showExport, includePaths, archiveService) {
            if (showExport) {
                exportJson = runCatching {
                    archiveService?.export(includePaths)?.let(LibraryArchiveCodec::encode)
                }.getOrNull().orEmpty()
            }
        }
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text(strings.exportLibrary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = includePaths,
                            onCheckedChange = { includePaths = it },
                        )
                        Text(strings.exportIncludePaths)
                    }
                    OutlinedTextField(
                        value = exportJson,
                        onValueChange = {},
                        readOnly = true,
                        minLines = 8,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(exportJson)) },
                    enabled = exportJson.isNotBlank(),
                ) {
                    Text(strings.copyAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExport = false }) {
                    Text(strings.closeAction)
                }
            },
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text(strings.importLibrary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importMessage = null
                        },
                        label = { Text(strings.importPasteLabel) },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = importPolicy == LibraryImportPolicy.MERGE,
                            onClick = { importPolicy = LibraryImportPolicy.MERGE },
                            label = { Text(strings.importPolicyMerge) },
                        )
                        FilterChip(
                            selected = importPolicy == LibraryImportPolicy.REPLACE,
                            onClick = { importPolicy = LibraryImportPolicy.REPLACE },
                            label = { Text(strings.importPolicyReplace) },
                        )
                    }
                    importMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importText.isNotBlank() && !importing,
                    onClick = {
                        importing = true
                        archiveScope.launch {
                            val decoded = LibraryArchiveCodec.decode(importText)
                            if (decoded.isFailure) {
                                importMessage = strings.importInvalid
                            } else {
                                val result = archiveService?.import(
                                    decoded.getOrThrow(),
                                    importPolicy,
                                )
                                coordinator.reloadLibrary()
                                libraryController.restore()
                                importMessage = if (result != null) {
                                    "${strings.importedEntries}: ${result.entriesImported} · " +
                                        "${strings.updatedEntries}: ${result.entriesUpdated} · " +
                                        "${strings.skippedEntries}: ${result.entriesSkipped}"
                                } else {
                                    strings.importInvalid
                                }
                            }
                            importing = false
                        }
                    },
                ) {
                    Text(strings.importAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) {
                    Text(strings.closeAction)
                }
            },
        )
    }

    if (showWatchImport) {
        AlertDialog(
            onDismissRequest = { showWatchImport = false },
            title = { Text(strings.watchFolderImport) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        strings.watchFolderHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = watchPath,
                        onValueChange = {
                            watchPath = it
                            watchMessage = null
                        },
                        label = { Text(strings.watchFolderPath) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    watchMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = watchPath.isNotBlank() && !watching,
                    onClick = {
                        watching = true
                        archiveScope.launch {
                            try {
                                val result = folderImportService?.import(watchPath)
                                coordinator.reloadLibrary()
                                checkToken++
                                watchMessage = if (result != null) {
                                    "${strings.importedEntries}: ${result.added} · " +
                                        "${strings.skippedEntries}: ${result.skipped}"
                                } else {
                                    strings.folderImportFailed
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                coordinator.reloadLibrary()
                                watchMessage = strings.folderImportFailed
                            } finally {
                                watching = false
                            }
                        }
                    },
                ) {
                    Text(strings.importAction)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWatchImport = false }) {
                    Text(strings.closeAction)
                }
            },
        )
    }

    processingEntry?.let { entry ->
        mediaProcessor?.let { processor ->
            MediaToolsDialog(entry, processor, coordinator) { processingEntry = null }
        }
    }

    if (showStorage) {
        StorageDialog(
            report = storageReport,
            loading = storageLoading,
            failed = storageFailed,
            onRetry = {
                storageLoading = true
                checkToken++
            },
            onImport = if (folderImportService != null) {
                {
                    showStorage = false
                    watchPath = downloadsDirectory
                    watchMessage = null
                    showWatchImport = true
                }
            } else {
                null
            },
            onDismiss = { showStorage = false },
            offlineStorage = offlineStorage,
        )
    }

    val selectedPortableTab = portableBackupTab
    if (selectedPortableTab != null && portableBackupController != null) {
        PortableBackupDialog(
            controller = portableBackupController,
            picker = portableArchivePicker,
            initialTab = selectedPortableTab,
            nowEpochMillis = nowEpochMillis,
            onRestored = {
                offlineRefresh++
                checkToken++
                onPortableRestored()
            },
            onDismiss = { portableBackupTab = null },
        )
    }

    if (showBulkRename && bulkFileService != null) {
        val selectedEntries = entries.filter { it.id in bulkSelection }
        val plan = BulkFilePlanner.planRename(
            entries = selectedEntries,
            pattern = bulkRenamePattern,
            targetExists = { path -> fileAvailability.exists(path) },
        )
        BulkPlanDialog(
            title = strings.bulkRename,
            plan = plan,
            input = {
                OutlinedTextField(
                    value = bulkRenamePattern,
                    onValueChange = { bulkRenamePattern = it },
                    label = { Text(strings.bulkPattern) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    strings.bulkPatternHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            message = bulkMessage,
            applying = bulkApplying,
            onApply = {
                bulkApplying = true
                archiveScope.launch {
                    val result = bulkFileService.apply(plan)
                    coordinator.reloadLibrary()
                    bulkMessage = if (result.success) strings.bulkApplied else strings.bulkFailed
                    bulkApplying = false
                }
            },
            onClose = { showBulkRename = false },
        )
    }

    if (showBulkMove && bulkFileService != null) {
        val selectedEntries = entries.filter { it.id in bulkSelection }
        val plan = BulkFilePlanner.planMove(
            entries = selectedEntries,
            destinationDirectory = bulkMoveDestination,
            targetExists = { path -> fileAvailability.exists(path) },
        )
        BulkPlanDialog(
            title = strings.bulkMove,
            plan = plan,
            input = {
                OutlinedTextField(
                    value = bulkMoveDestination,
                    onValueChange = { bulkMoveDestination = it },
                    label = { Text(strings.bulkDestination) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            message = bulkMessage,
            applying = bulkApplying,
            onApply = {
                bulkApplying = true
                archiveScope.launch {
                    val result = bulkFileService.apply(plan)
                    coordinator.reloadLibrary()
                    bulkMessage = if (result.success) strings.bulkApplied else strings.bulkFailed
                    bulkApplying = false
                }
            },
            onClose = { showBulkMove = false },
        )
    }

    assigningEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { assigningEntry = null },
            title = { Text(strings.assignCollections) },
            text = {
                if (collections.isEmpty()) {
                    Text(strings.noCollections)
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        collections.forEach { collection ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = entry.id in membership[collection.id].orEmpty(),
                                    onCheckedChange = {
                                        libraryController.toggleEntry(collection.id, entry.id)
                                    },
                                )
                                Text(collection.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { assigningEntry = null }) {
                    Text(strings.closeAction)
                }
            },
            dismissButton = if (collections.isEmpty()) {
                {
                    TextButton(
                        onClick = {
                            assigningEntry = null
                            showCreateCollection = true
                        },
                    ) {
                        Text(strings.newCollection)
                    }
                }
            } else {
                null
            },
        )
    }

    if (confirmDeleteCollection && activeCollectionId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCollection = false },
            title = { Text(strings.deleteCollection) },
            text = { Text(strings.deleteCollectionHint) },
            confirmButton = {
                TextButton(
                    onClick = {
                        libraryController.deleteCollection(activeCollectionId)
                        selectedCollectionId = null
                        confirmDeleteCollection = false
                    },
                ) {
                    Text(strings.deleteCollection)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteCollection = false }) {
                    Text(strings.cancelAction)
                }
            },
        )
    }

    if (confirmRemoveDuplicates) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDuplicates = false },
            title = { Text(strings.removeDuplicates) },
            text = { Text(strings.removeDuplicatesHint) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveDuplicates = false
                        requestRemoval(duplicateRemovalIds)
                    },
                ) {
                    Text(strings.removeDuplicatesConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveDuplicates = false }) {
                    Text(strings.cancelAction)
                }
            },
        )
    }

    val currentStudyReq = studyRequest
    if (currentStudyReq != null && studyService != null) {
        val targetEntry = entries.firstOrNull { it.id == currentStudyReq.entryId }
        if (targetEntry != null) {
            StudySheetDialog(
                entry = targetEntry,
                itemMissing = targetEntry.id in missingIds,
                queueContext = sortedItems.map { it.entry },
                studyService = studyService,
                studyCaptureService = studyCaptureService,
                studyEditorCoordinator = resolvedEditorCoordinator,
                studyAbRepeatController = studyAbRepeatController,
                studyExporter = studyExporter,
                hostLinkCapability = hostLinkCapability,
                language = resolvedLanguage,
                initialLinkedTimestampMillis = currentStudyReq.initialLinkedTimestampMillis,
                openedViaLocalLink = currentStudyReq.openedViaLocalLink,
                onStudyDataChanged = { studyRefreshToken++ },
                onClose = { studyRequest = null },
            )
        }
    }

    val removalState = pendingRemoval
    if (removalState != null && studyService != null) {
        StudyRemovalDialog(
            previews = removalState.previews,
            exporter = studyExporter,
            hostLinkCapability = hostLinkCapability,
            language = resolvedLanguage,
            onConfirmRemoveAndPurge = {
                pendingRemoval = null
                coordinator.removeFromLibrary(removalState.entryIds)
                removalState.onCommitted()
                playbackScope.launch {
                    withContext(Dispatchers.Default) {
                        removalState.entryIds.forEach { entryId ->
                            studyService.deleteAllStudyDataForEntry(entryId)
                        }
                    }
                    studyRefreshToken++
                }
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCard(
    item: LibraryItem,
    localArtworkPath: String?,
    studied: Boolean,
    wide: Boolean,
    selectable: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onStudy: (() -> Unit)?,
    onProcess: (() -> Unit)?,
    onCollections: () -> Unit,
    onRemove: () -> Unit,
    onPlay: (() -> Unit)?,
    mediaActions: MediaActions,
) {
    val strings = LocalStrings.current
    val entry = item.entry

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selectable) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onSelect,
                        modifier = Modifier.semantics { contentDescription = entry.title },
                    )
                }
                if (localArtworkPath != null || !entry.thumbnailUrl.isNullOrBlank()) {
                    MediaThumbnail(
                        url = entry.thumbnailUrl,
                        localArtworkPath = localArtworkPath,
                        modifier = Modifier
                            .width(if (wide) 240.dp else 96.dp)
                            .aspectRatio(16f / 9f),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ProviderIcon(entry.source, Modifier.size(18.dp))
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val name = entry.filePath.substringAfterLast('/').substringAfterLast('\\')
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        listOfNotNull(formatBytes(entry.sizeBytes), kindLabel(entry.kind, strings))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.missing) {
                        Text(
                            strings.missingFile,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (item.duplicate) {
                        Text(
                            strings.possibleDuplicate,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (studied) {
                        Text(
                            strings.study.studiedBadge,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("card-studied-${entry.id}"),
                        )
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onPlay != null) {
                    TextButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = strings.playAction)
                        Text(strings.playAction)
                    }
                }
                if (mediaActions.canShare) {
                    TextButton(
                        onClick = {
                            mediaActions.share(
                                listOf(entry.toShareable()),
                                strings.shareChooserTitle,
                            )
                        },
                        enabled = !item.missing,
                    ) {
                        Text(strings.shareAction)
                    }
                }
                FileActionButtons(entry.toShareable(), mediaActions, enabled = !item.missing)
                onStudy?.let { action ->
                    TextButton(
                        onClick = action,
                        modifier = Modifier.testTag("study-entry-${entry.id}"),
                    ) {
                        Text(strings.study.openStudyAction)
                    }
                }
                onProcess?.let { action ->
                    TextButton(onClick = action) { Text(strings.features[FeatureText.PROCESSING]) }
                }
                TextButton(onClick = onDetails) { Text(strings.detailsAction) }
                TextButton(onClick = onCollections) { Text(strings.assignCollections) }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag("remove-entry-${entry.id}"),
                ) { Text(strings.removeFromLibrary) }
            }
        }
    }
}

@Composable
private fun BulkPlanDialog(
    title: String,
    plan: BulkPlan,
    input: @Composable () -> Unit,
    message: String?,
    applying: Boolean,
    onApply: () -> Unit,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                input()
                Text(strings.bulkPreview, style = MaterialTheme.typography.labelLarge)
                if (plan.changes.isEmpty()) {
                    Text(strings.notAvailable, style = MaterialTheme.typography.bodySmall)
                } else {
                    plan.changes.take(5).forEach { change ->
                        Text(
                            "${fileNameOf(change.fromPath)} → ${fileNameOf(change.toPath)}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (plan.conflicts.isNotEmpty()) {
                    Text(
                        strings.bulkConflicts,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    plan.conflicts.take(5).forEach { conflict ->
                        Text(
                            conflict,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onApply,
                enabled = plan.canApply && !applying,
            ) {
                Text(strings.bulkApply)
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(strings.closeAction) }
        },
    )
}

@Composable
private fun LibraryDetailsDialog(
    item: LibraryItem,
    mediaProbe: MediaProbe,
    mediaTagger: MediaTagger?,
    onClose: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onSaveMetadata: (String, String?) -> Unit,
    offlineState: OfflineLibraryState?,
    onTogglePin: (OfflineAssetView, Boolean) -> Unit,
    onOfflineCleanup: (Set<String>) -> Unit,
    onOfflineAction: (RecoveryAction) -> Unit,
    onRetryReadiness: () -> Unit,
    canImportSubtitle: Boolean,
    onImportSubtitle: () -> Unit,
    subtitleMessage: String?,
) {
    val strings = LocalStrings.current
    val entry = item.entry
    val scope = rememberCoroutineScope()
    var technical by remember(entry.id) { mutableStateOf<MediaTechnicalInfo?>(null) }
    var analyzing by remember(entry.id) { mutableStateOf(!item.missing) }
    var tagText by remember(entry.id) { mutableStateOf("") }
    var editing by remember(entry.id) { mutableStateOf(false) }
    var titleText by remember(entry.id) { mutableStateOf(entry.title) }
    var authorText by remember(entry.id) { mutableStateOf(entry.author.orEmpty()) }
    var embedTags by remember(entry.id) { mutableStateOf(entry.kind == MediaKind.AUDIO) }
    var saving by remember(entry.id) { mutableStateOf(false) }
    var message by remember(entry.id) { mutableStateOf<String?>(null) }

    fun save() {
        val title = titleText.trim()
        if (title.isEmpty() || saving) return
        val author = authorText.trim().ifBlank { null }
        onSaveMetadata(title, author)
        if (embedTags && mediaTagger != null && entry.kind == MediaKind.AUDIO) {
            scope.launch {
                saving = true
                val ok = mediaTagger.writeTags(
                    path = entry.filePath,
                    metadata = AudioMetadata(title, author),
                    coverUrl = entry.thumbnailUrl,
                )
                saving = false
                message = if (ok) strings.metadataSaved else strings.metadataFailed
                editing = false
            }
        } else {
            message = strings.metadataSaved
            editing = false
        }
    }

    LaunchedEffect(entry.id, item.missing) {
        if (item.missing) {
            analyzing = false
            return@LaunchedEffect
        }
        technical = withContext(Dispatchers.Default) { mediaProbe.probe(entry.filePath) }
        analyzing = false
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            if (editing) {
                TextButton(onClick = ::save, enabled = titleText.isNotBlank() && !saving) {
                    Text(strings.saveMetadata)
                }
            } else {
                TextButton(onClick = onClose) { Text(strings.closeAction) }
            }
        },
        dismissButton = {
            if (editing) {
                TextButton(
                    onClick = {
                        editing = false
                        message = null
                        titleText = entry.title
                        authorText = entry.author.orEmpty()
                    },
                ) {
                    Text(strings.cancelAction)
                }
            } else {
                TextButton(
                    onClick = {
                        editing = true
                        message = null
                        titleText = entry.title
                        authorText = entry.author.orEmpty()
                    },
                ) {
                    Text(strings.editMetadata)
                }
            }
        },
        title = {
            Text(if (editing) titleText else entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editing) {
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it },
                        label = { Text(strings.titleLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = authorText,
                        onValueChange = { authorText = it },
                        label = { Text(strings.authorLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (entry.kind == MediaKind.AUDIO && mediaTagger != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = embedTags,
                                onCheckedChange = { embedTags = it },
                            )
                            Text(strings.embedMetadata)
                        }
                    }
                } else {
                    DetailRow(strings.authorLabel, entry.author ?: "—")
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DetailRow(strings.detailsPath, entry.filePath)
                formatBytes(entry.sizeBytes)?.let { DetailRow(strings.detailsSize, it) }
                DetailRow(strings.detailsKind, kindLabel(entry.kind, strings))
                DetailRow(
                    strings.detailsStatus,
                    when {
                        item.missing -> strings.missingFile
                        item.duplicate -> strings.possibleDuplicate
                        else -> strings.statusAvailable
                    },
                )
                offlineState?.let { state ->
                    OfflineReadinessCard(
                        state = state,
                        onTogglePin = onTogglePin,
                        onCleanup = onOfflineCleanup,
                        onAction = onOfflineAction,
                        onRetry = onRetryReadiness,
                    )
                }
                if (canImportSubtitle) {
                    TextButton(onClick = onImportSubtitle) {
                        Text(strings.offline.importSubtitle)
                    }
                }
                subtitleMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when {
                    analyzing -> Text(
                        strings.analyzing,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    item.missing -> Unit

                    technical == null -> Text(
                        strings.notAvailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        val info = technical!!
                        info.durationMillis?.let {
                            DetailRow(
                                strings.detailsDuration,
                                formatDuration(it / 1000).orEmpty(),
                            )
                        }
                        if (info.width != null && info.height != null) {
                            DetailRow(strings.detailsResolution, "${info.width}x${info.height}")
                        }
                        info.videoCodec?.let { DetailRow(strings.detailsVideoCodec, it) }
                        info.audioCodec?.let { DetailRow(strings.detailsAudioCodec, it) }
                        info.bitrateKbps?.let { DetailRow(strings.detailsBitrate, "$it kbps") }
                        info.container?.let { DetailRow(strings.detailsContainer, it) }
                    }
                }

                HorizontalDivider()
                Text(strings.tagsLabel, style = MaterialTheme.typography.labelLarge)
                if (entry.tags.isEmpty()) {
                    Text(
                        strings.tagsEmpty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        entry.tags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = { onRemoveTag(tag) },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = strings.removeTagAction,
                                    )
                                },
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = tagText,
                        onValueChange = { tagText = it },
                        label = { Text(strings.tagInputLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onAddTag(tagText)
                            tagText = ""
                        },
                        enabled = tagText.isNotBlank(),
                    ) {
                        Text(strings.addTagAction)
                    }
                }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun LibraryEntry.toShareable() = ShareableMedia(
    path = filePath,
    title = title,
    kind = kind,
)

private fun kindLabel(kind: MediaKind, strings: AppStrings): String = when (kind) {
    MediaKind.VIDEO -> strings.kindVideo
    MediaKind.AUDIO -> strings.kindAudio
    MediaKind.SUBTITLE -> strings.features[FeatureText.SUBTITLES]
}
