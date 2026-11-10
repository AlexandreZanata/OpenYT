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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DiagnosticsFormatter
import com.openyt.core.application.DiagnosticsProvider
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.FolderPicker
import com.openyt.core.application.LibraryController
import com.openyt.core.application.PlatformDiagnostics
import com.openyt.core.application.SafDestinationPicker
import com.openyt.core.application.SettingsController
import com.openyt.core.application.UpdateChecker
import com.openyt.core.application.UpdateInfo
import com.openyt.core.application.UpdateInstaller
import com.openyt.core.application.UrlOpener
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.ThemeMode
import com.openyt.ui.i18n.LocalStrings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    engine: DownloadEngine,
    downloadsDirectory: String,
    settingsController: SettingsController,
    coordinator: DownloadCoordinator,
    libraryController: LibraryController,
    diagnosticsProvider: DiagnosticsProvider,
    updateChecker: UpdateChecker,
    updateInstaller: UpdateInstaller,
    urlOpener: UrlOpener,
    safDestinationPicker: SafDestinationPicker? = null,
    folderPicker: FolderPicker? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val settings by settingsController.settings.collectAsState()
    val tasks by coordinator.tasks.collectAsState()
    val library by coordinator.library.collectAsState()
    val collections by libraryController.collections.collectAsState()
    val scope = rememberCoroutineScope()
    var capabilities by remember { mutableStateOf<EngineCapabilities?>(null) }
    var diagnostics by remember { mutableStateOf<PlatformDiagnostics?>(null) }
    var copied by remember { mutableStateOf<Boolean?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var updatesChecked by remember { mutableStateOf(false) }
    var installingUpdate by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(engine) {
        capabilities = engine.capabilities()
    }
    LaunchedEffect(diagnosticsProvider) {
        diagnostics = diagnosticsProvider.snapshot()
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

        if (safDestinationPicker != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.safDestinationLabel, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (settings.storageTreeUri != null) {
                            strings.safConfigured
                        } else {
                            strings.safNotConfigured
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        strings.safDestinationHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                safDestinationPicker.pick { uri ->
                                    if (uri != null) settingsController.setStorageTreeUri(uri)
                                }
                            },
                        ) {
                            Text(strings.chooseSafFolder)
                        }
                        if (settings.storageTreeUri != null) {
                            TextButton(
                                onClick = { settingsController.setStorageTreeUri(null) },
                            ) {
                                Text(strings.clearSafFolder)
                            }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.updatesTitle, style = MaterialTheme.typography.labelLarge)
                SettingRow(strings.diagnosticsAppVersion, diagnostics?.appVersion ?: "—")
                TextButton(
                    enabled = diagnostics != null && !checkingUpdates,
                    onClick = {
                        val version = diagnostics?.appVersion ?: return@TextButton
                        scope.launch {
                            checkingUpdates = true
                            update = updateChecker.latest(version)
                            updatesChecked = true
                            checkingUpdates = false
                        }
                    },
                ) {
                    Text(strings.checkUpdates)
                }
                if (checkingUpdates) {
                    Text(
                        strings.checkingUpdates,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val availableUpdate = update
                if (availableUpdate != null) {
                    Text("${strings.updateAvailable} v${availableUpdate.version}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !installingUpdate,
                            onClick = {
                                scope.launch {
                                    installingUpdate = true
                                    installMessage = null
                                    val installed = updateInstaller.install(availableUpdate)
                                    installMessage = if (installed) {
                                        strings.installerOpened
                                    } else {
                                        strings.installFailed
                                    }
                                    installingUpdate = false
                                }
                            },
                        ) {
                            Text(strings.installUpdate)
                        }
                        TextButton(onClick = { urlOpener.open(availableUpdate.releaseUrl) }) {
                            Text(strings.openRelease)
                        }
                    }
                    if (installingUpdate) {
                        Text(
                            strings.installingUpdate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    installMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (updatesChecked && !checkingUpdates) {
                    Text(
                        strings.upToDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.diagnosticsTitle, style = MaterialTheme.typography.labelLarge)
                val snapshot = diagnostics
                if (snapshot == null) {
                    Text(
                        strings.diagnosticsLoading,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SettingRow(strings.diagnosticsAppVersion, snapshot.appVersion)
                    SettingRow(strings.diagnosticsPlatform, snapshot.platform)
                    SettingRow(
                        strings.engineLabel,
                        snapshot.engineName + (snapshot.engineVersion?.let { " $it" } ?: ""),
                    )
                    SettingRow(strings.diagnosticsDownloads, snapshot.downloadDirectory, maxLines = 2)
                    snapshot.databasePath?.let {
                        SettingRow(strings.diagnosticsDatabase, it, maxLines = 2)
                    }
                    SettingRow(strings.diagnosticsTasks, tasks.size.toString())
                    SettingRow(strings.diagnosticsLibrary, library.size.toString())
                    SettingRow(strings.diagnosticsCollections, collections.size.toString())
                    if (snapshot.tools.isNotEmpty()) {
                        Text(strings.diagnosticsTools, style = MaterialTheme.typography.labelMedium)
                        snapshot.tools.forEach { tool ->
                            val status = if (tool.available) strings.supported else strings.notSupported
                            val version = tool.version?.let { " ($it)" }.orEmpty()
                            Text(
                                "${tool.name}: $status$version",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            val text = DiagnosticsFormatter.format(
                                diagnostics = snapshot,
                                taskCount = tasks.size,
                                libraryCount = library.size,
                                collectionCount = collections.size,
                            )
                            copied = diagnosticsProvider.copyToClipboard(text)
                        },
                    ) {
                        Text(strings.diagnosticsCopy)
                    }
                    copied?.let { success ->
                        Text(
                            if (success) strings.diagnosticsCopied else strings.diagnosticsCopyFailed,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (success) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
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
