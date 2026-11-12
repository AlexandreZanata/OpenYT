package com.openyt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openyt.core.domain.IndexUnavailableReason
import com.openyt.core.domain.InvalidQueryReason
import com.openyt.core.domain.TimedSearchExcerpt
import com.openyt.core.domain.TimedSearchIndexStatus
import com.openyt.core.domain.TimedSearchMatch
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.util.formatDuration

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimedSearchScopeIndicator(
    scope: TimedSearchScope,
    indexStatus: TimedSearchIndexStatus?,
    selectedLanguage: String?,
    availableLanguages: List<String>,
    onSelectLanguage: (String?) -> Unit,
    modifier: Modifier = Modifier,
    collectionName: String? = null,
    entryTitle: String? = null,
) {
    val strings = LocalStrings.current

    val scopeLabel = when (scope) {
        is TimedSearchScope.Library -> strings.timedSearch.searchScopeLibrary
        is TimedSearchScope.Collection -> "${strings.timedSearch.searchScopeCollection}: ${collectionName ?: scope.collectionId}"
        is TimedSearchScope.Entry -> "${strings.timedSearch.searchScopeEntry}: ${entryTitle ?: scope.entryId}"
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Subtitles,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = scopeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (indexStatus != null) {
            val statusColor = when (indexStatus) {
                TimedSearchIndexStatus.READY -> MaterialTheme.colorScheme.primary
                TimedSearchIndexStatus.PARTIALLY_INDEXED -> MaterialTheme.colorScheme.tertiary
            }
            val statusLabel = when (indexStatus) {
                TimedSearchIndexStatus.READY -> strings.timedSearch.indexStatusReady
                TimedSearchIndexStatus.PARTIALLY_INDEXED -> strings.timedSearch.indexStatusPartiallyIndexed
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusColor.copy(alpha = 0.12f),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }

        if (availableLanguages.isNotEmpty()) {
            FilterChip(
                selected = selectedLanguage == null,
                onClick = { onSelectLanguage(null) },
                label = { Text(strings.timedSearch.allLanguages, style = MaterialTheme.typography.labelSmall) },
            )
            for (lang in availableLanguages) {
                FilterChip(
                    selected = selectedLanguage == lang,
                    onClick = { onSelectLanguage(if (selectedLanguage == lang) null else lang) },
                    label = { Text(lang.uppercase(), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
fun TimedSearchExcerptText(
    excerpt: TimedSearchExcerpt,
    modifier: Modifier = Modifier,
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    val highlightBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)

    val annotated = buildAnnotatedString {
        append(excerpt.text)
        for (range in excerpt.highlightRanges) {
            val validStart = range.start.coerceIn(0, excerpt.text.length)
            val validEnd = (range.endInclusive + 1).coerceIn(validStart, excerpt.text.length)
            if (validStart < validEnd) {
                addStyle(
                    SpanStyle(
                        color = highlightColor,
                        fontWeight = FontWeight.Bold,
                        background = highlightBg,
                    ),
                    validStart,
                    validEnd,
                )
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics {
            contentDescription = excerpt.text
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
fun formatTimestampMs(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
fun TimedSearchMatchCard(
    match: TimedSearchMatch,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val title = match.entryTitle ?: match.entryId
    val formattedTime = formatTimestampMs(match.startMs)
    val semanticAction = strings.timedSearch.timestampAccessibilityLabel(
        title,
        formattedTime,
        match.excerpt.text,
    )

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.Spacebar)) {
                    onPlay()
                    true
                } else false
            },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!match.language.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = match.language!!.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (!match.speaker.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = match.speaker!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            TimedSearchExcerptText(
                excerpt = match.excerpt,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .testTag("timed-search-play-btn")
                        .semantics {
                            contentDescription = semanticAction
                        }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.Spacebar)) {
                                onPlay()
                                true
                            } else false
                        },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
fun TimedSearchStateView(
    result: TimedSearchResult?,
    isLoading: Boolean,
    onImportSubtitle: (() -> Unit)? = null,
    onReindex: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Text(
                        text = strings.timedSearch.loading,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            result == null -> {
                Text(
                    text = strings.timedSearch.enterQueryPrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            result is TimedSearchResult.InvalidQuery -> {
                val message = when (result.reason) {
                    InvalidQueryReason.EMPTY -> strings.timedSearch.enterQueryPrompt
                    InvalidQueryReason.EXCEEDS_LENGTH_LIMIT -> strings.timedSearch.queryTooLong
                    InvalidQueryReason.EXCEEDS_TOKEN_LIMIT -> strings.timedSearch.queryTooManyTerms
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            result is TimedSearchResult.IndexUnavailable -> {
                val explanation = when (result.reason) {
                    IndexUnavailableReason.NO_SUBTITLE_TRACKS -> strings.timedSearch.noSubtitleTracks
                    IndexUnavailableReason.INDEX_PENDING_OR_STALE -> strings.timedSearch.indexPendingOrStale
                    IndexUnavailableReason.INDEX_FAILED -> strings.timedSearch.indexFailed
                    IndexUnavailableReason.ENTRY_NOT_FOUND -> strings.timedSearch.entryNotFound
                    IndexUnavailableReason.COLLECTION_EMPTY -> strings.timedSearch.collectionEmpty
                }

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = explanation,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (result.reason == IndexUnavailableReason.NO_SUBTITLE_TRACKS && onImportSubtitle != null) {
                                OutlinedButton(onClick = onImportSubtitle) {
                                    Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.timedSearch.importSubtitleAction)
                                }
                            }
                            if (onReindex != null) {
                                OutlinedButton(onClick = onReindex) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.timedSearch.reindexAction)
                                }
                            }
                        }
                    }
                }
            }

            result is TimedSearchResult.Success && result.items.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = strings.timedSearch.noMatches,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
