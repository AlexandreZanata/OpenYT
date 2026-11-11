package com.openyt.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.openyt.ui.components.ProviderIcon
import com.openyt.ui.theme.OpenYtTheme
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ProviderIconsUiTest {
    @Test
    fun everySharedProviderVectorLoads() = runComposeUiTest {
        val providers = listOf(
            "youtube", "instagram", "tiktok", "x", "facebook", "soundcloud", "vimeo", "twitch",
            "reddit", "pinterest", "dailymotion", "bandcamp", "threads", "telegram", "bilibili", "rumble", "nebula", "applepodcasts", "unlisted-source",
        )
        setContent {
            OpenYtTheme(darkTheme = true) {
                Surface {
                    Column {
                        providers.forEach { ProviderIcon(it, Modifier.size(24.dp)) }
                    }
                }
            }
        }
        providers.forEach { onNodeWithContentDescription(it).assertExists() }
    }
}
