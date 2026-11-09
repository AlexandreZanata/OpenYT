package com.openyt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.SettingsController
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.ThemeMode
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.DownloadsScreen
import com.openyt.ui.screens.HomeScreen
import com.openyt.ui.screens.LibraryScreen
import com.openyt.ui.screens.SettingsScreen
import com.openyt.ui.theme.OpenYtTheme

@Composable
fun OpenYtApp(
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    settingsController: SettingsController,
    downloadsDirectory: String,
    initialSharedUrl: String? = null,
    onSharedUrlConsumed: () -> Unit = {},
) {
    val settings by settingsController.settings.collectAsState()
    val strings = stringsFor(settings.language)

    LaunchedEffect(settings.maxConcurrentDownloads) {
        coordinator.setMaxConcurrentDownloads(settings.maxConcurrentDownloads)
    }

    CompositionLocalProvider(LocalStrings provides strings) {
        OpenYtTheme(
            darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            },
        ) {
            AppShell(
                engine = engine,
                coordinator = coordinator,
                settingsController = settingsController,
                downloadsDirectory = downloadsDirectory,
                initialSharedUrl = initialSharedUrl,
                onSharedUrlConsumed = onSharedUrlConsumed,
            )
        }
    }
}

private fun stringsFor(language: AppLanguage): AppStrings = when (language) {
    AppLanguage.PT_BR -> PtBrStrings
    AppLanguage.EN -> EnStrings
}

private data class ShellTab(val icon: ImageVector, val label: String)

@Composable
private fun AppShell(
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    settingsController: SettingsController,
    downloadsDirectory: String,
    initialSharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
) {
    val strings = LocalStrings.current
    val tabs = listOf(
        ShellTab(Icons.Default.Home, strings.tabHome),
        ShellTab(Icons.Default.Download, strings.tabDownloads),
        ShellTab(Icons.Default.VideoLibrary, strings.tabLibrary),
        ShellTab(Icons.Default.Settings, strings.tabSettings),
    )
    var selected by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) selected = 0
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val content: @Composable (PaddingValues) -> Unit = { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selected) {
                    0 -> HomeScreen(
                        engine = engine,
                        coordinator = coordinator,
                        downloadsDirectory = downloadsDirectory,
                        onDownloadStarted = { selected = 1 },
                        initialSharedUrl = initialSharedUrl,
                        onSharedUrlConsumed = onSharedUrlConsumed,
                    )

                    1 -> DownloadsScreen(coordinator)
                    2 -> LibraryScreen(coordinator)
                    else -> SettingsScreen(engine, downloadsDirectory, settingsController)
                }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    tabs.forEachIndexed { index, tab ->
                        NavigationRailItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) { content(PaddingValues(0.dp)) }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize()) { content(padding) }
            }
        }
    }
}
