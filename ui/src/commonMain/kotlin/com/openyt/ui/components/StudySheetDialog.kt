package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyCaptureAndNavigationService
import com.openyt.core.application.StudyEditorCoordinator
import com.openyt.core.application.StudyExportOptions
import com.openyt.core.application.StudyHostLinkCapability
import com.openyt.core.application.StudyMarkdownExporter
import com.openyt.core.application.StudyService
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.application.TextExportOutcome
import com.openyt.core.application.TextFileExporter
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.EntryStudyOverview
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.StudyAbRepeatState
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyEditorDraft
import com.openyt.core.domain.StudyNavigationDecision
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyResult
import com.openyt.ui.i18n.LocalStrings
import kotlinx.coroutines.launch

/**
 * Entry-focused Study workspace dialog uniting manual study completion,
 * deep-link timestamp preview (explicit Play only), timed notes & bookmarks
 * backed by [StudyEditorCoordinator], A/B loop controls, anchor health actions,
 * in-entry search/filter, and safe Markdown export.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudySheetDialog(
    entry: LibraryEntry,
    itemMissing: Boolean,
    queueContext: List<LibraryEntry>,
    studyService: StudyService,
    studyCaptureService: StudyCaptureAndNavigationService?,
    studyEditorCoordinator: StudyEditorCoordinator,
    studyAbRepeatController: StudyAbRepeatController?,
    studyExporter: TextFileExporter?,
    hostLinkCapability: StudyHostLinkCapability,
    language: AppLanguage,
    initialLinkedTimestampMillis: Long? = null,
    openedViaLocalLink: Boolean = false,
    onStudyDataChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val studyStrings = strings.study
    val scope = rememberCoroutineScope()

    var refreshToken by remember(entry.id) { mutableStateOf(0) }
    var overview by remember(entry.id) {
        mutableStateOf(
            EntryStudyOverview(
                bundle = EntryStudyBundle(
                    entryId = entry.id,
                    entryExists = true,
                    currentMediaRevision = entry.mediaRevision,
                    studyState = EntryStudyState(entry.id, false, null, 0L),
                    notes = emptyList(),
                    bookmarks = emptyList(),
                    savedRanges = emptyList(),
                ),
                knownDurationMillis = null,
                notes = emptyList(),
                bookmarks = emptyList(),
                savedRanges = emptyList(),
            ),
        )
    }

    val editorState by studyEditorCoordinator.state.collectAsState()
    val abStateFlow = studyAbRepeatController?.state
    val abState = if (abStateFlow != null) {
        abStateFlow.collectAsState().value
    } else {
        StudyAbRepeatState(isSupported = false)
    }

    var searchQuery by remember(entry.id) { mutableStateOf("") }
    var itemFilter by remember(entry.id) { mutableStateOf(StudyItemFilter.ALL) }
    var includeLocalLinks by remember(entry.id, hostLinkCapability.supportsClickableEntryLinks) {
        mutableStateOf(hostLinkCapability.supportsClickableEntryLinks)
    }
    var statusBanner by remember(entry.id) { mutableStateOf<String?>(null) }
    var pendingCloseRequest by remember(entry.id) { mutableStateOf(false) }

    LaunchedEffect(entry.id, refreshToken) {
        overview = studyCaptureService?.loadEntryStudyOverview(entry.id)
            ?: EntryStudyOverview(
                bundle = studyService.loadEntryStudyBundle(entry.id),
                knownDurationMillis = null,
                notes = emptyList(),
                bookmarks = emptyList(),
                savedRanges = emptyList(),
            ).let { raw ->
                com.openyt.core.application.StudyAnchorInspector.inspectBundle(raw.bundle, null)
            }
    }

    fun bumpStudyChanged() {
        refreshToken++
        onStudyDataChanged()
    }

    fun tryCloseDialog() {
        val focused = editorState.focusedDraft
        if (focused != null && focused.entryId == entry.id && focused.isDirty) {
            when (studyEditorCoordinator.requestCloseFocusedDraft()) {
                is StudyNavigationDecision.PromptConfirmDiscard -> pendingCloseRequest = true
                StudyNavigationDecision.Proceeding,
                StudyNavigationDecision.PreservedInBackground -> onClose()
            }
        } else {
            studyEditorCoordinator.onNormalNavigation()
            onClose()
        }
    }

    val bundle = overview.bundle
    val activeDraft = editorState.focusedDraft?.takeIf { it.entryId == entry.id }
    val normalizedQuery = searchQuery.trim().lowercase()

    val filteredNotes = remember(overview.notes, normalizedQuery) {
        if (normalizedQuery.isEmpty()) overview.notes
        else overview.notes.filter {
            it.note.body.lowercase().contains(normalizedQuery) ||
                StudyMarkdownExporter.formatInterval(it.note.timestampMillis, it.note.endMillis).contains(normalizedQuery)
        }
    }
    val filteredBookmarks = remember(overview.bookmarks, normalizedQuery) {
        if (normalizedQuery.isEmpty()) overview.bookmarks
        else overview.bookmarks.filter {
            it.bookmark.label.lowercase().contains(normalizedQuery) ||
                formatTimecodeForInput(it.bookmark.timestampMillis).contains(normalizedQuery)
        }
    }
    val filteredRanges = remember(overview.savedRanges, normalizedQuery) {
        if (normalizedQuery.isEmpty()) overview.savedRanges
        else overview.savedRanges.filter {
            it.range.label.orEmpty().lowercase().contains(normalizedQuery) ||
                StudyMarkdownExporter.formatInterval(it.range.startMillis, it.range.endMillis).contains(normalizedQuery)
        }
    }

    AlertDialog(
        onDismissRequest = ::tryCloseDialog,
        title = {
            Text(
                "${studyStrings.studyTitle}: ${entry.title}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }.testTag("study-sheet-title"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = ::tryCloseDialog,
                modifier = Modifier.testTag("study-sheet-close"),
            ) {
                Text(strings.closeAction)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (openedViaLocalLink) {
                    val linkedTimeLabel = initialLinkedTimestampMillis?.let(::formatTimecodeForInput)
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("study-deeplink-preview"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (linkedTimeLabel != null) {
                                    studyStrings.deepLinkPreviewTitle(linkedTimeLabel)
                                } else {
                                    studyStrings.deepLinkNoTimestampTitle
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(studyStrings.deepLinkPreviewSubtitle, style = MaterialTheme.typography.bodySmall)
                            if (itemMissing) {
                                Text(
                                    studyStrings.deepLinkMediaMissing,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else if (studyCaptureService != null) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val result = studyCaptureService.openEntryAtTimestamp(
                                                entryId = entry.id,
                                                timestampMillis = initialLinkedTimestampMillis ?: 0L,
                                                queueContext = queueContext,
                                            )
                                            statusBanner = translateNavigationFailure(result, studyStrings)
                                        }
                                    },
                                    modifier = Modifier.testTag("study-deeplink-play"),
                                ) {
                                    Text(
                                        if (linkedTimeLabel != null) {
                                            studyStrings.deepLinkPlayAtTimeAction(linkedTimeLabel)
                                        } else {
                                            strings.playAction
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (itemMissing) {
                    Text(
                        studyStrings.missingMediaMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                StudiedToggleRow(
                    studied = bundle.studyState.isStudied,
                    label = studyStrings.markStudiedAction,
                    completedSubtitle = studyStrings.studiedCompletedLabel,
                    onToggle = { checked ->
                        scope.launch {
                            when (val res = studyService.setEntryStudied(entry.id, checked)) {
                                is StudyResult.Success -> {
                                    statusBanner = null
                                    bumpStudyChanged()
                                }
                                is StudyResult.Failure -> {
                                    statusBanner = translateStudyFailure(res.failure, studyStrings)
                                }
                            }
                        }
                    },
                )

                // Export controls
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hostLinkCapability.supportsClickableEntryLinks) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeLocalLinks,
                                onCheckedChange = { includeLocalLinks = it },
                                modifier = Modifier.testTag("study-export-links-checkbox"),
                            )
                            Text(studyStrings.includeLocalLinksToggle, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            studyStrings.linksNotSupportedHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (studyExporter?.canExport == true) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val markdown = StudyMarkdownExporter.exportEntryBundle(
                                        entry = entry,
                                        bundle = bundle,
                                        options = StudyExportOptions(
                                            includeLocalLinks = includeLocalLinks && hostLinkCapability.supportsClickableEntryLinks,
                                            language = language,
                                        ),
                                        hostCapability = hostLinkCapability,
                                    )
                                    val outcome = studyExporter.exportMarkdown(
                                        suggestedFilename = StudyMarkdownExporter.suggestedFilename(entry.title),
                                        content = markdown,
                                    )
                                    statusBanner = when (outcome) {
                                        is TextExportOutcome.Exported -> studyStrings.exportSuccess(outcome.destinationLabel)
                                        TextExportOutcome.Canceled -> studyStrings.exportCanceled
                                        is TextExportOutcome.Failed -> studyStrings.exportFailed(outcome.message)
                                    }
                                }
                            },
                            modifier = Modifier.testTag("study-export-button"),
                        ) {
                            Text(studyStrings.exportMarkdownAction)
                        }
                    }
                }

                statusBanner?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("study-status-banner"),
                    )
                }

                // Quick creation actions
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val captured = studyCaptureService?.captureLoadedPosition(expectedEntryId = entry.id)
                                val time = (captured as? StudyCaptureResult.Success)?.value?.timestampMillis ?: 0L
                                val gen = (captured as? StudyCaptureResult.Success)?.value?.sessionGeneration
                                if (captured is StudyCaptureResult.Failure && abState.entryId == entry.id) {
                                    statusBanner = translateCaptureFailure(captured.failure, studyStrings)
                                }
                                studyEditorCoordinator.openNewNoteDraft(entry.id, time, capturedGeneration = gen)
                            }
                        },
                        modifier = Modifier.testTag("study-new-note-button"),
                    ) {
                        Text(studyStrings.newNoteAction)
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val captured = studyCaptureService?.captureLoadedPosition(expectedEntryId = entry.id)
                                val time = (captured as? StudyCaptureResult.Success)?.value?.timestampMillis ?: 0L
                                val gen = (captured as? StudyCaptureResult.Success)?.value?.sessionGeneration
                                studyEditorCoordinator.openNewBookmarkDraft(entry.id, time, capturedGeneration = gen)
                            }
                        },
                        modifier = Modifier.testTag("study-new-bookmark-button"),
                    ) {
                        Text(studyStrings.newBookmarkAction)
                    }
                }

                if (activeDraft != null) {
                    val errorMsg = editorState.lastError?.let { translateCaptureFailure(it, studyStrings) }
                    StudyDraftEditorCard(
                        draft = activeDraft,
                        canCaptureLivePosition = !itemMissing && studyCaptureService != null,
                        errorMessage = errorMsg,
                        strings = studyStrings,
                        cancelLabel = strings.cancelAction,
                        onTimestampChange = { ms ->
                            when (activeDraft) {
                                is StudyEditorDraft.NoteDraft ->
                                    studyEditorCoordinator.updateNoteDraft(activeDraft.key, timestampMillis = ms)
                                is StudyEditorDraft.BookmarkDraft ->
                                    studyEditorCoordinator.updateBookmarkDraft(activeDraft.key, timestampMillis = ms)
                            }
                        },
                        onEndTimestampChange = { endMs ->
                            if (activeDraft is StudyEditorDraft.NoteDraft) {
                                studyEditorCoordinator.updateNoteDraft(
                                    activeDraft.key,
                                    endMillis = endMs,
                                    clearEndMillis = endMs == null,
                                )
                            }
                        },
                        onBodyOrLabelChange = { text ->
                            when (activeDraft) {
                                is StudyEditorDraft.NoteDraft ->
                                    studyEditorCoordinator.updateNoteDraft(activeDraft.key, body = text)
                                is StudyEditorDraft.BookmarkDraft ->
                                    studyEditorCoordinator.updateBookmarkDraft(activeDraft.key, label = text)
                            }
                        },
                        onCaptureLive = {
                            scope.launch {
                                val cap = studyCaptureService?.captureLoadedPosition(expectedEntryId = entry.id)
                                when (cap) {
                                    is StudyCaptureResult.Success -> {
                                        when (activeDraft) {
                                            is StudyEditorDraft.NoteDraft ->
                                                studyEditorCoordinator.updateNoteDraft(
                                                    activeDraft.key,
                                                    timestampMillis = cap.value.timestampMillis,
                                                )
                                            is StudyEditorDraft.BookmarkDraft ->
                                                studyEditorCoordinator.updateBookmarkDraft(
                                                    activeDraft.key,
                                                    timestampMillis = cap.value.timestampMillis,
                                                )
                                        }
                                        statusBanner = null
                                    }
                                    is StudyCaptureResult.Failure ->
                                        statusBanner = translateCaptureFailure(cap.failure, studyStrings)
                                    null -> statusBanner = studyStrings.abDisabledHint
                                }
                            }
                        },
                        onSave = {
                            if (studyCaptureService == null) {
                                scope.launch {
                                    val saved = when (activeDraft) {
                                        is StudyEditorDraft.NoteDraft -> {
                                            val existingNoteId = activeDraft.noteId
                                            if (existingNoteId == null) {
                                                studyService.createNote(
                                                    entryId = entry.id,
                                                    timestampMillis = activeDraft.draftTimestampMillis,
                                                    body = activeDraft.draftBody,
                                                    endMillis = activeDraft.draftEndMillis,
                                                )
                                            } else {
                                                studyService.updateNote(
                                                    noteId = existingNoteId,
                                                    body = activeDraft.draftBody,
                                                    timestampMillis = activeDraft.draftTimestampMillis,
                                                    endMillis = activeDraft.draftEndMillis,
                                                    clearEndMillis = activeDraft.originalEndMillis != null && activeDraft.draftEndMillis == null,
                                                )
                                            }
                                        }
                                        is StudyEditorDraft.BookmarkDraft -> {
                                            val existingBookmarkId = activeDraft.bookmarkId
                                            if (existingBookmarkId == null) {
                                                studyService.createBookmark(
                                                    entryId = entry.id,
                                                    timestampMillis = activeDraft.draftTimestampMillis,
                                                    label = activeDraft.draftLabel,
                                                )
                                            } else {
                                                studyService.updateBookmark(
                                                    bookmarkId = existingBookmarkId,
                                                    label = activeDraft.draftLabel,
                                                    timestampMillis = activeDraft.draftTimestampMillis,
                                                )
                                            }
                                        }
                                    }
                                    when (saved) {
                                        is StudyResult.Success -> {
                                            studyEditorCoordinator.markDraftSaved(activeDraft.key)
                                            statusBanner = null
                                            bumpStudyChanged()
                                        }
                                        is StudyResult.Failure ->
                                            statusBanner = translateStudyFailure(saved.failure, studyStrings)
                                    }
                                }
                            } else {
                                scope.launch {
                                    val committed = when (activeDraft) {
                                        is StudyEditorDraft.NoteDraft ->
                                            studyEditorCoordinator.saveNoteDraft(activeDraft.key, studyCaptureService)
                                        is StudyEditorDraft.BookmarkDraft ->
                                            studyEditorCoordinator.saveBookmarkDraft(activeDraft.key, studyCaptureService)
                                    }
                                    if (committed is StudyCaptureResult.Success) {
                                        statusBanner = null
                                        bumpStudyChanged()
                                    }
                                }
                            }
                        },
                        onCancel = {
                            when (studyEditorCoordinator.requestCancelDraft(activeDraft.key)) {
                                is StudyNavigationDecision.PromptConfirmDiscard -> pendingCloseRequest = false
                                else -> Unit
                            }
                        },
                    )
                }

                if (studyAbRepeatController != null) {
                    val liveSessionActive = !itemMissing && abState.isSupported &&
                        abState.status != AbRepeatStatus.UNSUPPORTED
                    StudyAbRepeatCard(
                        abState = abState,
                        canUseLiveSession = liveSessionActive,
                        strings = studyStrings,
                        onSetA = { scope.launch { studyAbRepeatController.markPointA() } },
                        onSetB = { scope.launch { studyAbRepeatController.markPointB() } },
                        onStartLoop = { scope.launch { studyAbRepeatController.start(resumeIfPaused = true) } },
                        onStopLoop = { scope.launch { studyAbRepeatController.stop(clearBounds = false) } },
                        onClearAb = { scope.launch { studyAbRepeatController.clear() } },
                        onSaveRange = { label ->
                            val a = abState.pointAMillis ?: return@StudyAbRepeatCard
                            val b = abState.pointBMillis ?: return@StudyAbRepeatCard
                            scope.launch {
                                when (val res = studyService.saveRange(entry.id, a, b, label, overview.knownDurationMillis)) {
                                    is StudyResult.Success -> {
                                        statusBanner = null
                                        bumpStudyChanged()
                                    }
                                    is StudyResult.Failure ->
                                        statusBanner = translateStudyFailure(res.failure, studyStrings)
                                }
                            }
                        },
                    )
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(studyStrings.searchStudyLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("study-search-input"),
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = itemFilter == StudyItemFilter.ALL,
                        onClick = { itemFilter = StudyItemFilter.ALL },
                        label = { Text(studyStrings.filterAll) },
                    )
                    FilterChip(
                        selected = itemFilter == StudyItemFilter.NOTES,
                        onClick = { itemFilter = StudyItemFilter.NOTES },
                        label = { Text(studyStrings.filterNotes) },
                    )
                    FilterChip(
                        selected = itemFilter == StudyItemFilter.BOOKMARKS,
                        onClick = { itemFilter = StudyItemFilter.BOOKMARKS },
                        label = { Text(studyStrings.filterBookmarks) },
                    )
                    FilterChip(
                        selected = itemFilter == StudyItemFilter.RANGES,
                        onClick = { itemFilter = StudyItemFilter.RANGES },
                        label = { Text(studyStrings.filterRanges) },
                    )
                }

                if (itemFilter == StudyItemFilter.ALL || itemFilter == StudyItemFilter.NOTES) {
                    Text(studyStrings.notesSection, style = MaterialTheme.typography.titleSmall)
                    if (filteredNotes.isEmpty()) {
                        Text(studyStrings.noNotesHint, style = MaterialTheme.typography.bodySmall)
                    } else {
                        filteredNotes.forEach { noteItem ->
                            AnnotatedNoteRow(
                                item = noteItem,
                                strings = studyStrings,
                                onOpen = {
                                    scope.launch {
                                        val res = studyCaptureService?.openNoteTimestamp(noteItem.note.id, queueContext)
                                        statusBanner = res?.let { translateNavigationFailure(it, studyStrings) }
                                            ?: studyStrings.abDisabledHint
                                    }
                                },
                                onEdit = { studyEditorCoordinator.openExistingNoteDraft(noteItem.note) },
                                onDelete = {
                                    scope.launch {
                                        studyService.deleteNote(noteItem.note.id)
                                        bumpStudyChanged()
                                    }
                                },
                                onReanchor = {
                                    scope.launch {
                                        studyCaptureService?.reanchorNoteToCurrentRevision(noteItem.note.id)
                                        bumpStudyChanged()
                                    }
                                },
                                onClamp = {
                                    val dur = overview.knownDurationMillis ?: return@AnnotatedNoteRow
                                    scope.launch {
                                        studyCaptureService?.clampOutOfRangeNoteToDuration(noteItem.note.id, dur)
                                        bumpStudyChanged()
                                    }
                                },
                            )
                        }
                    }
                }

                if (itemFilter == StudyItemFilter.ALL || itemFilter == StudyItemFilter.BOOKMARKS) {
                    Text(studyStrings.bookmarksSection, style = MaterialTheme.typography.titleSmall)
                    if (filteredBookmarks.isEmpty()) {
                        Text(studyStrings.noBookmarksHint, style = MaterialTheme.typography.bodySmall)
                    } else {
                        filteredBookmarks.forEach { bmItem ->
                            AnnotatedBookmarkRow(
                                item = bmItem,
                                strings = studyStrings,
                                onOpen = {
                                    scope.launch {
                                        val res = studyCaptureService?.openBookmarkTimestamp(bmItem.bookmark.id, queueContext)
                                        statusBanner = res?.let { translateNavigationFailure(it, studyStrings) }
                                            ?: studyStrings.abDisabledHint
                                    }
                                },
                                onEdit = { studyEditorCoordinator.openExistingBookmarkDraft(bmItem.bookmark) },
                                onDelete = {
                                    scope.launch {
                                        studyService.deleteBookmark(bmItem.bookmark.id)
                                        bumpStudyChanged()
                                    }
                                },
                                onReanchor = {
                                    scope.launch {
                                        studyCaptureService?.reanchorBookmarkToCurrentRevision(bmItem.bookmark.id)
                                        bumpStudyChanged()
                                    }
                                },
                                onClamp = {
                                    val dur = overview.knownDurationMillis ?: return@AnnotatedBookmarkRow
                                    scope.launch {
                                        studyCaptureService?.clampOutOfRangeBookmarkToDuration(bmItem.bookmark.id, dur)
                                        bumpStudyChanged()
                                    }
                                },
                            )
                        }
                    }
                }

                if (itemFilter == StudyItemFilter.ALL || itemFilter == StudyItemFilter.RANGES) {
                    Text(studyStrings.rangesSection, style = MaterialTheme.typography.titleSmall)
                    if (filteredRanges.isEmpty()) {
                        Text(studyStrings.noRangesHint, style = MaterialTheme.typography.bodySmall)
                    } else {
                        filteredRanges.forEach { rangeItem ->
                            AnnotatedRangeRow(
                                item = rangeItem,
                                canActivate = !itemMissing && studyAbRepeatController != null,
                                strings = studyStrings,
                                onActivate = {
                                    scope.launch {
                                        val res = studyAbRepeatController?.activateSavedRange(rangeItem.range)
                                        if (res is com.openyt.core.domain.AbRepeatResult.Failure) {
                                            statusBanner = res.failure.message
                                        }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        studyService.deleteSavedRange(rangeItem.range.id)
                                        bumpStudyChanged()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    )

    if (editorState.pendingDiscardPrompt != null) {
        AlertDialog(
            onDismissRequest = {
                pendingCloseRequest = false
                studyEditorCoordinator.keepEditing()
            },
            title = { Text(studyStrings.discardChangesTitle) },
            text = { Text(studyStrings.discardChangesMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val closeAfter = pendingCloseRequest
                        pendingCloseRequest = false
                        studyEditorCoordinator.confirmDiscard()
                        if (closeAfter) onClose()
                    },
                    modifier = Modifier.testTag("study-discard-confirm"),
                ) {
                    Text(studyStrings.discardChangesAction)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingCloseRequest = false
                        studyEditorCoordinator.keepEditing()
                    },
                    modifier = Modifier.testTag("study-discard-keep"),
                ) {
                    Text(studyStrings.keepEditingAction)
                }
            },
        )
    }
}
