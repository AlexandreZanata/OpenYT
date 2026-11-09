package com.clipbau.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.clipbau.core.application.DownloadCoordinator
import com.clipbau.core.domain.DownloadEngine
import com.clipbau.ui.i18n.LocalStrings
import com.clipbau.ui.i18n.PtBrStrings
import com.clipbau.ui.i18n.AppStrings
import com.clipbau.ui.screens.DownloadsScreen
import com.clipbau.ui.screens.HomeScreen
import com.clipbau.ui.screens.LibraryScreen
import com.clipbau.ui.screens.SettingsScreen
import com.clipbau.ui.theme.ClipBauTheme

@Composable
fun ClipBauApp(
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    downloadsDirectory: String,
    strings: AppStrings = PtBrStrings,
) {
    CompositionLocalProvider(LocalStrings provides strings) {
        ClipBauTheme {
            AppShell(engine, coordinator, downloadsDirectory)
        }
    }
}

private data class ShellTab(val icon: ImageVector, val label: String)

@Composable
private fun AppShell(
    engine: DownloadEngine,
    coordinator: DownloadCoordinator,
    downloadsDirectory: String,
) {
    val strings = LocalStrings.current
    val tabs = listOf(
        ShellTab(Icons.Default.Home, strings.tabHome),
        ShellTab(Icons.Default.Download, strings.tabDownloads),
        ShellTab(Icons.Default.VideoLibrary, strings.tabLibrary),
        ShellTab(Icons.Default.Settings, strings.tabSettings),
    )
    var selected by rememberSaveable { mutableStateOf(0) }

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
                    )

                    1 -> DownloadsScreen(coordinator)
                    2 -> LibraryScreen(coordinator)
                    else -> SettingsScreen(engine, downloadsDirectory)
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
