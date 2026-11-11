package com.openyt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openyt.core.domain.DownloadEngine
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import kotlinx.coroutines.CancellationException

@Composable
fun SourcesDialog(engine: DownloadEngine, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var sources by remember { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(engine, retry) {
        sources = null
        sources = try { engine.supportedSources() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }
    val found = remember(sources, query) { sources.orEmpty().filter { it.contains(query, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.features[FeatureText.SUPPORTED_SOURCES]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.features[FeatureText.SOURCES_HINT])
                OutlinedTextField(query, { query = it }, label = { Text(strings.features[FeatureText.SEARCH_SOURCES]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when {
                    sources == null -> CircularProgressIndicator()
                    sources.isNullOrEmpty() -> Text(strings.features[FeatureText.SOURCES_FAILED])
                    found.isEmpty() -> Text(strings.features[FeatureText.NO_RESULTS])
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(found, key = { it }) { source ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProviderIcon(source, Modifier.size(24.dp))
                                Text(source)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.closeAction) } },
        dismissButton = { if (sources?.isEmpty() == true) TextButton(onClick = { retry++ }) { Text(strings.retryAction) } },
    )
}
