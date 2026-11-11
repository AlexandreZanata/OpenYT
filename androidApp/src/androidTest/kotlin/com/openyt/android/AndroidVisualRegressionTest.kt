package com.openyt.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.painterResource
import com.openyt.ui.resources.Res
import com.openyt.ui.resources.allDrawableResources
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

@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidVisualRegressionTest {
    @Test
    fun allProviderVectorsRenderOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val vectors = Res.allDrawableResources.filterKeys { it.startsWith("source_") }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    OpenYtTheme(darkTheme = true) {
                        Surface {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    providers.forEach { ProviderIcon(it, Modifier.size(24.dp)) }
                                    vectors.forEach { (name, resource) -> Icon(painterResource(resource), name, Modifier.size(24.dp)) }
                                }
                                Text("All provider vectors rendered")
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val root = awaitText("All provider vectors rendered")
            assertTrue("Expected the expanded icon collection", vectors.size >= 140)
            assertNotNull(root)
            providers.forEach { provider ->
                assertTrue("Missing provider $provider", nodesWithText(root, provider).isNotEmpty())
            }
            vectors.keys.forEach { name -> assertTrue("Missing vector $name", nodesWithText(root, name).isNotEmpty()) }
            saveValidationScreenshot("android-provider-icons.png")
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
            assertTrue(nodesWithText(root, PtBrStrings.closeAction).any { it.isVisibleToUser })
            assertTrue(nodesWithText(root, PtBrStrings.watchFolderImport).any { it.isVisibleToUser })
            saveValidationScreenshot("android-storage-large-text.png")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun largeScreenLibraryRemainsReachableWithDpadNavigation() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun shell(command: String) { automation.executeShellCommand(command).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } } }
        var activity: MainActivity? = null
        try {
            shell("wm size 1600x1000")
            shell("wm density 160")
            val context = instrumentation.targetContext
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            instrumentation.runOnMainSync { (activity.application as OpenYtApplication).container.settingsController.setLanguage(com.openyt.core.domain.AppLanguage.EN) }
            awaitText(com.openyt.ui.i18n.EnStrings.tabLibrary)
            val homeRoot = awaitText(com.openyt.ui.i18n.EnStrings.tabHome)
            var focus = nodesWithText(homeRoot, com.openyt.ui.i18n.EnStrings.tabHome).first()
            while (!focus.isFocusable && focus.parent != null) focus = focus.parent
            assertTrue("Home navigation item must accept keyboard focus", focus.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            val root = awaitText(com.openyt.ui.i18n.EnStrings.libraryEmpty)
            assertTrue(nodesWithText(root, com.openyt.ui.i18n.EnStrings.libraryEmpty).any { it.isVisibleToUser })
            saveValidationScreenshot("android-tablet-dpad.png")
        } finally {
            runCatching { saveValidationScreenshot("android-tablet-final-state.png") }
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            shell("wm size reset")
            shell("wm density reset")
        }
    }

    private fun nodesWithText(root: android.view.accessibility.AccessibilityNodeInfo, text: String): List<android.view.accessibility.AccessibilityNodeInfo> {
        val found = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) found.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(pending::addLast) }
        }
        return found
    }

    private fun awaitText(text: String): android.view.accessibility.AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        repeat(100) {
            val root = automation.rootInActiveWindow
            if (root != null && nodesWithText(root, text).isNotEmpty()) return root
            Thread.sleep(50)
        }
        error("Screen did not expose $text")
    }

    private val providers = listOf(
        "youtube", "instagram", "tiktok", "x", "facebook", "soundcloud", "vimeo", "twitch",
        "reddit", "pinterest", "dailymotion", "bandcamp", "threads", "telegram", "bilibili", "rumble",
    )
}
