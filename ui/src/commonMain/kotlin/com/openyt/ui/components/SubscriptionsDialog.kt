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
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    var kind by remember { mutableStateOf(SubscriptionKind.PLAYLIST) }
    var includeExisting by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.features[FeatureText.AUTOMATION]) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(strings.features[FeatureText.AUTOMATION_HINT]) }
                if (saveFailed) item { Text(strings.features[FeatureText.RULE_SAVE_FAILED]) }
                if (adding) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        if (invalid) Text(strings.features[FeatureText.RULE_INVALID])
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    invalid = !controller.add(name, url, kind, minutes.toIntOrNull() ?: 0, includeExisting)
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(rule.enabled, { scope.launch { try { controller.setEnabled(rule.id, !rule.enabled) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { saveFailed = true } } }, label = { Text(strings.features[FeatureText.ENABLED]) })
                            TextButton(enabled = rule.id !in busy, onClick = { scope.launch { controller.check(rule.id) } }) { Text(strings.features[FeatureText.CHECK_NOW]) }
                            TextButton(onClick = { scope.launch { try { controller.remove(rule.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { saveFailed = true } } }) { Text(strings.removeFromLibrary) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.closeAction) } },
        dismissButton = { TextButton(onClick = { adding = !adding }) { Text(strings.features[FeatureText.ADD_RULE]) } },
    )
}
