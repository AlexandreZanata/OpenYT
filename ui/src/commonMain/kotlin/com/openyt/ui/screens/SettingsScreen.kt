package com.openyt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openyt.core.application.FolderPicker
import com.openyt.core.application.SettingsController
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.ThemeMode
import com.openyt.ui.i18n.LocalStrings

@Composable
fun SettingsScreen(
    engine: DownloadEngine,
    downloadsDirectory: String,
    settingsController: SettingsController,
    folderPicker: FolderPicker? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val settings by settingsController.settings.collectAsState()
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
        Text(
            strings.settingsTitle,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.themeLabel, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.SYSTEM,
                        onClick = { settingsController.setTheme(ThemeMode.SYSTEM) },
                        label = { Text(strings.themeSystem) },
                    )
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.LIGHT,
                        onClick = { settingsController.setTheme(ThemeMode.LIGHT) },
                        label = { Text(strings.themeLight) },
                    )
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.DARK,
                        onClick = { settingsController.setTheme(ThemeMode.DARK) },
                        label = { Text(strings.themeDark) },
                    )
                }

                HorizontalDivider()

                Text(strings.languageLabel, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.language == AppLanguage.PT_BR,
                        onClick = { settingsController.setLanguage(AppLanguage.PT_BR) },
                        label = { Text("Português") },
                    )
                    FilterChip(
                        selected = settings.language == AppLanguage.EN,
                        onClick = { settingsController.setLanguage(AppLanguage.EN) },
                        label = { Text("English") },
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(strings.concurrencyLabel, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                settingsController.setMaxConcurrentDownloads(
                                    settings.maxConcurrentDownloads - 1,
                                )
                            },
                            enabled = settings.maxConcurrentDownloads > AppSettings.MIN_CONCURRENT,
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = strings.decreaseConcurrency)
                        }
                        Text(
                            settings.maxConcurrentDownloads.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        IconButton(
                            onClick = {
                                settingsController.setMaxConcurrentDownloads(
                                    settings.maxConcurrentDownloads + 1,
                                )
                            },
                            enabled = settings.maxConcurrentDownloads < AppSettings.MAX_CONCURRENT,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = strings.increaseConcurrency)
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingRow(strings.downloadFolderLabel, downloadsDirectory, maxLines = 3)
                if (folderPicker != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                folderPicker.pickFolder(downloadsDirectory)
                                    ?.let(settingsController::setDownloadsDirectory)
                            },
                        ) {
                            Text(strings.changeFolder)
                        }
                        if (settings.downloadsDirectory != null) {
                            TextButton(onClick = { settingsController.setDownloadsDirectory(null) }) {
                                Text(strings.useDefaultFolder)
                            }
                        }
                    }
                    Text(
                        strings.shortcutsHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
