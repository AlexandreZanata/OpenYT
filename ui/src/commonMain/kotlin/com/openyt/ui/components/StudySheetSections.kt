package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openyt.core.domain.AbRepeatFailure
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.domain.AnnotatedSavedRange
import com.openyt.core.domain.AnnotatedStudyBookmark
import com.openyt.core.domain.AnnotatedStudyNote
import com.openyt.core.domain.StudyAbRepeatState
import com.openyt.core.domain.StudyAnchorHealth
import com.openyt.core.domain.StudyAnchorStatus
import com.openyt.core.domain.StudyEditorDraft
import com.openyt.ui.i18n.StudyStrings

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyAbRepeatCard(
    abState: StudyAbRepeatState,
    canUseLiveSession: Boolean,
    strings: StudyStrings,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onStartLoop: () -> Unit,
    onStopLoop: () -> Unit,
    onClearAb: () -> Unit,
    onSaveRange: (label: String?) -> Unit,
) {
    var rangeLabel by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("study-ab-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.abRepeatSection, style = MaterialTheme.typography.titleSmall)
            val pointAText = abState.pointAMillis?.let(::formatTimecodeForInput)
            val pointBText = abState.pointBMillis?.let(::formatTimecodeForInput)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(strings.abPointALabel(pointAText), style = MaterialTheme.typography.bodySmall)
                Text(strings.abPointBLabel(pointBText), style = MaterialTheme.typography.bodySmall)
                val statusText = when {
                    !abState.isSupported || !canUseLiveSession -> strings.abDisabledHint
                    abState.status == AbRepeatStatus.ACTIVE -> strings.abLoopActiveLabel(abState.loopCount)
                    abState.status == AbRepeatStatus.READY || abState.hasValidRange -> strings.abLoopReadyLabel
                    else -> strings.abStatusIdle
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!abState.isSupported || !canUseLiveSession) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            abState.lastError?.let { failure ->
                val errorText = when (failure) {
                    is AbRepeatFailure.CapabilityUnsupported -> strings.capabilityUnsupportedMessage
                    is AbRepeatFailure.PlayerStopped -> strings.playerStoppedMessage
                    is AbRepeatFailure.PlayerNotReady -> strings.playerPreparingMessage
                    is AbRepeatFailure.PlayerError -> strings.playerErrorMessage
                    is AbRepeatFailure.UnknownDuration -> strings.abDisabledHint
                    else -> failure.message
                }
                Text(
                    errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("study-ab-error"),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val supported = abState.isSupported && canUseLiveSession
                TextButton(
                    onClick = onSetA,
                    enabled = supported,
                    modifier = Modifier.testTag("study-ab-set-a"),
                ) {
                    Text(strings.setPointAAction)
                }
                TextButton(
                    onClick = onSetB,
                    enabled = supported,
                    modifier = Modifier.testTag("study-ab-set-b"),
                ) {
                    Text(strings.setPointBAction)
                }
                if (abState.status == AbRepeatStatus.ACTIVE) {
                    TextButton(
                        onClick = onStopLoop,
                        modifier = Modifier.testTag("study-ab-stop"),
                    ) {
                        Text(strings.stopLoopAction)
                    }
                } else {
                    TextButton(
                        onClick = onStartLoop,
                        enabled = supported && abState.hasValidRange,
                        modifier = Modifier.testTag("study-ab-start"),
                    ) {
                        Text(strings.startLoopAction)
                    }
                }
                TextButton(
                    onClick = onClearAb,
                    enabled = abState.pointAMillis != null || abState.pointBMillis != null || abState.status == AbRepeatStatus.ACTIVE,
                    modifier = Modifier.testTag("study-ab-clear"),
                ) {
                    Text(strings.clearAbAction)
                }
            }
            if (abState.hasValidRange) {
                OutlinedTextField(
                    value = rangeLabel,
                    onValueChange = { rangeLabel = it },
                    label = { Text(strings.rangeLabelInput) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("study-ab-range-label"),
                )
                TextButton(
                    onClick = {
                        val trimmed = rangeLabel.trim().ifEmpty { null }
                        onSaveRange(trimmed)
                        rangeLabel = ""
                    },
                    modifier = Modifier.testTag("study-ab-save-range"),
                ) {
                    Text(strings.saveRangeAction)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyDraftEditorCard(
    draft: StudyEditorDraft,
    canCaptureLivePosition: Boolean,
    errorMessage: String?,
    strings: StudyStrings,
    cancelLabel: String,
    onTimestampChange: (Long) -> Unit,
    onEndTimestampChange: (Long?) -> Unit,
    onBodyOrLabelChange: (String) -> Unit,
    onCaptureLive: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val initialStart = when (draft) {
        is StudyEditorDraft.NoteDraft -> draft.draftTimestampMillis
        is StudyEditorDraft.BookmarkDraft -> draft.draftTimestampMillis
    }
    val initialEnd = (draft as? StudyEditorDraft.NoteDraft)?.draftEndMillis
    var startText by remember(draft.key, initialStart) {
        mutableStateOf(formatTimecodeForInput(initialStart))
    }
    var endText by remember(draft.key, initialEnd) {
        mutableStateOf(initialEnd?.let(::formatTimecodeForInput).orEmpty())
    }
    var timestampError by remember(draft.key) { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("study-draft-editor"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (draft) {
                    is StudyEditorDraft.NoteDraft -> strings.newNoteAction
                    is StudyEditorDraft.BookmarkDraft -> strings.newBookmarkAction
                },
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = startText,
                onValueChange = { value ->
                    startText = value
                    val parsed = parseUserTimestampMillis(value)
                    if (parsed != null) {
                        timestampError = null
                        onTimestampChange(parsed)
                    } else {
                        timestampError = strings.invalidTimestampFormat
                    }
                },
                label = { Text(strings.timestampInputLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("study-draft-start-input"),
            )
            if (draft is StudyEditorDraft.NoteDraft) {
                OutlinedTextField(
                    value = endText,
                    onValueChange = { value ->
                        endText = value
                        if (value.isBlank()) {
                            timestampError = null
                            onEndTimestampChange(null)
                        } else {
                            val parsed = parseUserTimestampMillis(value)
                            if (parsed != null) {
                                timestampError = null
                                onEndTimestampChange(parsed)
                            } else {
                                timestampError = strings.invalidTimestampFormat
                            }
                        }
                    },
                    label = { Text(strings.endTimestampInputLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("study-draft-end-input"),
                )
            }
            OutlinedTextField(
                value = when (draft) {
                    is StudyEditorDraft.NoteDraft -> draft.draftBody
                    is StudyEditorDraft.BookmarkDraft -> draft.draftLabel
                },
                onValueChange = onBodyOrLabelChange,
                label = {
                    Text(
                        when (draft) {
                            is StudyEditorDraft.NoteDraft -> strings.noteBodyLabel
                            is StudyEditorDraft.BookmarkDraft -> strings.bookmarkLabel
                        },
                    )
                },
                minLines = if (draft is StudyEditorDraft.NoteDraft) 2 else 1,
                maxLines = if (draft is StudyEditorDraft.NoteDraft) 6 else 1,
                modifier = Modifier.fillMaxWidth().testTag("study-draft-text-input"),
            )
            (timestampError ?: errorMessage)?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("study-draft-error"),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onCaptureLive,
                    enabled = canCaptureLivePosition,
                    modifier = Modifier.testTag("study-draft-capture"),
                ) {
                    Text(strings.captureTimestampAction)
                }
                TextButton(
                    onClick = {
                        val startOk = parseUserTimestampMillis(startText) != null
                        val endOk = endText.isBlank() || parseUserTimestampMillis(endText) != null
                        if (!startOk || !endOk) {
                            timestampError = strings.invalidTimestampFormat
                        } else {
                            onSave()
                        }
                    },
                    modifier = Modifier.testTag("study-draft-save"),
                ) {
                    Text(strings.saveAction)
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("study-draft-cancel"),
                ) {
                    Text(cancelLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnnotatedNoteRow(
    item: AnnotatedStudyNote,
    strings: StudyStrings,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReanchor: () -> Unit,
    onClamp: () -> Unit,
) {
    val note = item.note
    val interval = com.openyt.core.application.StudyMarkdownExporter.formatInterval(
        note.timestampMillis,
        note.endMillis,
    )
    Card(Modifier.fillMaxWidth().testTag("study-note-${note.id}")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("[$interval]", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(note.body, style = MaterialTheme.typography.bodyMedium)
            AnchorHealthActionsRow(
                health = item.anchorHealth,
                strings = strings,
                onReanchor = onReanchor,
                onClamp = onClamp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onOpen, modifier = Modifier.testTag("study-note-open-${note.id}")) {
                    Text(strings.openTimestampAction)
                }
                TextButton(onClick = onEdit, modifier = Modifier.testTag("study-note-edit-${note.id}")) {
                    Text(strings.editAction)
                }
                TextButton(onClick = onDelete, modifier = Modifier.testTag("study-note-delete-${note.id}")) {
                    Text(strings.deleteAction)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnnotatedBookmarkRow(
    item: AnnotatedStudyBookmark,
    strings: StudyStrings,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReanchor: () -> Unit,
    onClamp: () -> Unit,
) {
    val bookmark = item.bookmark
    val timeText = formatTimecodeForInput(bookmark.timestampMillis)
    Card(Modifier.fillMaxWidth().testTag("study-bookmark-${bookmark.id}")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("[$timeText] ${bookmark.label}", style = MaterialTheme.typography.bodyMedium)
            AnchorHealthActionsRow(
                health = item.anchorHealth,
                strings = strings,
                onReanchor = onReanchor,
                onClamp = onClamp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onOpen) { Text(strings.openTimestampAction) }
                TextButton(onClick = onEdit) { Text(strings.editAction) }
                TextButton(onClick = onDelete) { Text(strings.deleteAction) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnnotatedRangeRow(
    item: AnnotatedSavedRange,
    canActivate: Boolean,
    strings: StudyStrings,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    val range = item.range
    val labelText = com.openyt.core.application.StudyMarkdownExporter.formatInterval(range.startMillis, range.endMillis)
    Card(Modifier.fillMaxWidth().testTag("study-range-${range.id}")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = range.label?.takeIf { it.isNotBlank() }?.let { "$labelText — $it" } ?: labelText
            Text(title, style = MaterialTheme.typography.bodyMedium)
            AnchorHealthActionsRow(
                health = item.anchorHealth,
                strings = strings,
                onReanchor = null,
                onClamp = null,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onActivate, enabled = canActivate) { Text(strings.activateRangeAction) }
                TextButton(onClick = onDelete) { Text(strings.deleteAction) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnchorHealthActionsRow(
    health: StudyAnchorHealth,
    strings: StudyStrings,
    onReanchor: (() -> Unit)?,
    onClamp: (() -> Unit)?,
) {
    if (health.status == StudyAnchorStatus.VALID) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (health.isRevisionStale) {
            Text(
                strings.staleAnchorBadge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (health.isOutOfRange) {
            Text(
                strings.outOfRangeAnchorBadge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (health.isRevisionStale && onReanchor != null) {
                TextButton(onClick = onReanchor) { Text(strings.reanchorAction) }
            }
            if (health.isOutOfRange && onClamp != null) {
                TextButton(onClick = onClamp) { Text(strings.clampToDurationAction) }
            }
        }
    }
}

@Composable
internal fun StudiedToggleRow(
    studied: Boolean,
    label: String,
    completedSubtitle: String,
    onToggle: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = studied,
            onCheckedChange = onToggle,
            modifier = Modifier
                .semantics { contentDescription = label }
                .testTag("study-studied-checkbox"),
        )
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (studied) {
                Text(
                    completedSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
