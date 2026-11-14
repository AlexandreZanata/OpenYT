package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openyt.core.automation.SubscriptionController
import com.openyt.core.automation.SubscriptionKind
import com.openyt.core.automation.SubscriptionFilters
import com.openyt.core.automation.CandidateState
import com.openyt.ui.i18n.AutomationText
import com.openyt.ui.i18n.subscriptionReason
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionsDialog(controller: SubscriptionController, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val rules by controller.rules.collectAsState()
    val busy by controller.checkingIds.collectAsState()
    val failures by controller.failedIds.collectAsState()
    val previews by controller.previews.collectAsState()
    val waiting by controller.waitingReasons.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    var kind by remember { mutableStateOf(SubscriptionKind.PLAYLIST) }
    var includeExisting by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf(SubscriptionFilters()) }
    var minDuration by remember { mutableStateOf("") }
    var maxDuration by remember { mutableStateOf("") }
    var maxNew by remember { mutableStateOf("20") }
    var maxPending by remember { mutableStateOf("100") }
    var freeMiB by remember { mutableStateOf("") }
    var replay by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(controller) {
        try { controller.refreshProfiles() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { saveFailed = true }
    }
    replay?.let { selected ->
        AlertDialog(onDismissRequest = { replay = null }, title = { Text(strings.features[AutomationText.APPROVE_REPLAY]) },
            confirmButton = { TextButton(onClick = { replay = null; scope.launch { controller.retryCandidate(selected.first, selected.second); controller.check(selected.first) } }) { Text(strings.features[AutomationText.DOWNLOAD_EPISODE]) } },
            dismissButton = { TextButton(onClick = { replay = null }) { Text(strings.closeAction) } })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.features[FeatureText.AUTOMATION]) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(strings.features[FeatureText.AUTOMATION_HINT]) }
                controller.backgroundScheduler?.let { scheduler ->
                    item {
                        val enabled by scheduler.enabled.collectAsState()
                        Column {
                            FilterChip(enabled, { scope.launch { try { scheduler.setEnabled(!enabled) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { saveFailed = true } } }, label = { Text(strings.features[AutomationText.BACKGROUND]) })
                            Text(strings.features[AutomationText.BACKGROUND_HINT])
                        }
                    }
                }
                if (saveFailed) item { Text(strings.features[FeatureText.RULE_SAVE_FAILED]) }
                if (adding) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editId == null) {
                            OutlinedTextField(name, { name = it }, label = { Text(strings.titleLabel) })
                            OutlinedTextField(url, { url = it }, label = { Text(strings.urlLabel) })
                            OutlinedTextField(minutes, { minutes = it }, label = { Text(strings.features[FeatureText.INTERVAL_MINUTES]) })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(kind == SubscriptionKind.PLAYLIST, { kind = SubscriptionKind.PLAYLIST }, label = { Text(strings.features[FeatureText.PLAYLIST_WATCH]) })
                            FilterChip(kind == SubscriptionKind.FEED, { kind = SubscriptionKind.FEED }, label = { Text(strings.features[FeatureText.FEED_WATCH]) })
                        }
                        FlowRow {
                            Checkbox(includeExisting, { includeExisting = it })
                            Text(strings.features[FeatureText.INCLUDE_EXISTING])
                        }
                        }
                        OutlinedTextField(minDuration, { minDuration = it }, label = { Text(strings.features[AutomationText.MIN_DURATION]) })
                        OutlinedTextField(maxDuration, { maxDuration = it }, label = { Text(strings.features[AutomationText.MAX_DURATION]) })
                        OutlinedTextField(maxNew, { maxNew = it }, label = { Text(strings.features[AutomationText.MAX_NEW]) })
                        OutlinedTextField(maxPending, { maxPending = it }, label = { Text(strings.features[AutomationText.MAX_PENDING]) })
                        OutlinedTextField(freeMiB, { freeMiB = it }, label = { Text(strings.features[AutomationText.FREE_SPACE]) })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(filters.profileId == null, { filters = filters.copy(profileId = null) }, label = { Text(strings.features[AutomationText.DEFAULT_PROFILE]) })
                            profiles.forEach { profile ->
                                val label = when (profile.id) {
                                    com.openyt.core.domain.DownloadProfile.BUILTIN_STUDY -> strings.features[FeatureText.PROFILE_STUDY]
                                    com.openyt.core.domain.DownloadProfile.BUILTIN_AUDIO -> strings.features[FeatureText.PROFILE_AUDIO]
                                    com.openyt.core.domain.DownloadProfile.BUILTIN_SAVE_SPACE -> strings.features[FeatureText.PROFILE_SAVE_SPACE]
                                    com.openyt.core.domain.DownloadProfile.BUILTIN_TV -> strings.features[FeatureText.PROFILE_TV]
                                    else -> profile.name
                                }
                                FilterChip(filters.profileId == profile.id, { filters = filters.copy(profileId = profile.id) }, label = { Text(label) })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(filters.wifiOnly, { filters = filters.copy(wifiOnly = !filters.wifiOnly) }, label = { Text(strings.features[AutomationText.WIFI]) }, enabled = controller.supportsDeviceConditions || filters.wifiOnly)
                            FilterChip(filters.unmeteredOnly, { filters = filters.copy(unmeteredOnly = !filters.unmeteredOnly) }, label = { Text(strings.features[AutomationText.UNMETERED]) }, enabled = controller.supportsDeviceConditions || filters.unmeteredOnly)
                            FilterChip(filters.chargingOnly, { filters = filters.copy(chargingOnly = !filters.chargingOnly) }, label = { Text(strings.features[AutomationText.CHARGING]) }, enabled = controller.supportsDeviceConditions || filters.chargingOnly)
                        }
                        Text(strings.features[AutomationText.DEVICE_HINT])
                        if (invalid) Text(strings.features[FeatureText.RULE_INVALID])
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val free = freeMiB.toLongOrNull()
                                    val numbersValid = (minDuration.isBlank() || minDuration.toLongOrNull() != null) &&
                                        (maxDuration.isBlank() || maxDuration.toLongOrNull() != null) &&
                                        (freeMiB.isBlank() || free != null && free in 0..Long.MAX_VALUE / 1_048_576)
                                    val resolved = filters.copy(minimumDurationSeconds = minDuration.toLongOrNull(), maximumDurationSeconds = maxDuration.toLongOrNull(),
                                        maxNewPerCheck = maxNew.toIntOrNull() ?: 0, maxPending = maxPending.toIntOrNull() ?: 0, minimumFreeBytes = free?.times(1_048_576))
                                    invalid = !numbersValid || if (editId != null) !controller.updateFilters(editId!!, resolved)
                                        else !controller.add(name, url, kind, minutes.toIntOrNull() ?: 0, includeExisting, resolved)
                                    if (!invalid) adding = false
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { saveFailed = true }
                            }
                        }) { Text(strings.saveMetadata) }
                    }
                }
                items(rules, key = { it.id }) { rule ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(rule.name)
                        Text(rule.url, maxLines = 2)
                        if (rule.id in failures) Text(strings.features[FeatureText.RULE_FAILED])
                        if (rule.id in busy) Text(strings.features[FeatureText.WORKING])
                        waiting[rule.id]?.let { Text(strings.features.subscriptionReason(it)) }
                        if (rule.discoveryLimited) Text(strings.features[AutomationText.LIMIT_HINT])
                        Text("${strings.features[AutomationText.MAX_NEW]}: ${rule.filters.maxNewPerCheck} · ${strings.features[AutomationText.MAX_PENDING]}: ${rule.filters.maxPending}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(rule.enabled, { scope.launch { try { controller.setEnabled(rule.id, !rule.enabled) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { saveFailed = true } } }, label = { Text(strings.features[FeatureText.ENABLED]) })
                            TextButton(enabled = rule.id !in busy, onClick = { scope.launch { controller.check(rule.id) } }) { Text(strings.features[FeatureText.CHECK_NOW]) }
                            TextButton(enabled = rule.id !in busy, onClick = { scope.launch { controller.preview(rule.id) } }) { Text(strings.features[AutomationText.PREVIEW]) }
                            TextButton(enabled = rule.id !in busy, onClick = { scope.launch { controller.previewHistory(rule.id) } }) { Text(strings.features[AutomationText.HISTORY]) }
                            TextButton(onClick = {
                                editId = rule.id; adding = true; filters = rule.filters
                                minDuration = rule.filters.minimumDurationSeconds?.toString().orEmpty()
                                maxDuration = rule.filters.maximumDurationSeconds?.toString().orEmpty()
                                maxNew = rule.filters.maxNewPerCheck.toString(); maxPending = rule.filters.maxPending.toString()
                                freeMiB = rule.filters.minimumFreeBytes?.div(1_048_576)?.toString().orEmpty()
                            }) { Text(strings.features[AutomationText.EDIT]) }
                            TextButton(onClick = { scope.launch { try { controller.remove(rule.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { saveFailed = true } } }) { Text(strings.removeFromLibrary) }
                        }
                        (previews[rule.id] ?: rule.candidates).take(50).forEach { candidate ->
                            Text("${candidate.episode.title} · ${strings.features.subscriptionReason(candidate.reason)}")
                            if (candidate.state != CandidateState.QUEUED) {
                                TextButton(enabled = rule.id !in busy, onClick = { replay = rule.id to candidate.episode.id }) {
                                    Text(strings.features[AutomationText.RETRY])
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.closeAction) } },
        dismissButton = { TextButton(onClick = { adding = !adding; editId = null; filters = SubscriptionFilters(); minDuration = ""; maxDuration = ""; maxNew = "20"; maxPending = "100"; freeMiB = "" }) { Text(strings.features[FeatureText.ADD_RULE]) } },
    )
}
