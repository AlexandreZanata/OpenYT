package com.openyt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.EngineCapabilities
import com.openyt.ui.i18n.LocalStrings

@Composable
fun SettingsScreen(
    engine: DownloadEngine,
    downloadsDirectory: String,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var capabilities by remember { mutableStateOf<EngineCapabilities?>(null) }

    LaunchedEffect(engine) {
        capabilities = engine.capabilities()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(strings.settingsTitle, style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow(strings.downloadFolderLabel, downloadsDirectory, maxLines = 2)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingRow(strings.engineLabel, capabilities?.engineName ?: "—")
                SettingRow(strings.engineVersionLabel, capabilities?.engineVersion ?: "—")
                HorizontalDivider()
                SettingRow(
                    strings.pauseLabel,
                    if (capabilities?.supportsPause == true) strings.supported else strings.notSupported,
                )
                SettingRow(
                    strings.subtitlesLabel,
                    if (capabilities?.supportsSubtitles == true) strings.supported else strings.notSupported,
                )
            }
        }
        Text(
            strings.settingsHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, maxLines: Int = 1) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
