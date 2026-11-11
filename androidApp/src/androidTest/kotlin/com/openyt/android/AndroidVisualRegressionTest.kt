package com.openyt.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.core.application.ScannedMedia
import com.openyt.core.application.StorageReport
import com.openyt.core.domain.MediaKind
import com.openyt.ui.components.ProviderIcon
import com.openyt.ui.components.StorageDialog
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.theme.OpenYtTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVisualRegressionTest {
    @Test
    fun allProviderVectorsRenderOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    OpenYtTheme(darkTheme = true) {
                        Surface {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                providers.forEach { provider ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ProviderIcon(provider, Modifier.size(24.dp))
                                        Text(provider, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val root = awaitText("rumble")
            assertNotNull(root)
            providers.forEach { provider ->
                assertTrue("Missing provider $provider", root.findAccessibilityNodeInfosByText(provider).isNotEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun storageRecoveryButtonsRemainVisibleWithLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    OpenYtTheme(darkTheme = true) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(
                            LocalStrings provides PtBrStrings,
                            LocalDensity provides Density(density.density, fontScale = 1.5f),
                        ) {
                            StorageDialog(
                                report = StorageReport(
                                    totalEntries = 0,
                                    totalBytes = 0,
                                    largest = emptyList(),
                                    duplicateGroups = emptyList(),
                                    missing = emptyList(),
                                    orphanFiles = listOf(ScannedMedia(
                                        path = "/storage/emulated/0/Downloads/Curso Básico/Configuração e transmissão.mkv",
                                        title = "Configuração e transmissão",
                                        kind = MediaKind.VIDEO,
                                        sizeBytes = 100,
                                    )),
                                ),
                                loading = false,
                                failed = false,
                                onRetry = {},
                                onImport = {},
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val root = awaitText(PtBrStrings.closeAction)
            assertTrue(root.findAccessibilityNodeInfosByText(PtBrStrings.closeAction).any { it.isVisibleToUser })
            assertTrue(root.findAccessibilityNodeInfosByText(PtBrStrings.watchFolderImport).any { it.isVisibleToUser })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun awaitText(text: String): android.view.accessibility.AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(100) {
            val root = automation.rootInActiveWindow
            if (root?.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true) return root
            Thread.sleep(50)
        }
        error("Screen did not expose $text")
    }

    private val providers = listOf(
        "youtube", "instagram", "tiktok", "x", "facebook", "soundcloud", "vimeo", "twitch",
        "reddit", "pinterest", "dailymotion", "bandcamp", "threads", "telegram", "bilibili", "rumble",
    )
}
