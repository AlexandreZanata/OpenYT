package com.openyt.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.openyt.core.application.OfflineAssetView
import com.openyt.core.application.OfflineLibraryState
import com.openyt.core.application.OfflineStorageBreakdown
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.MissingRequirement
import com.openyt.core.domain.OfflineReadiness
import com.openyt.core.domain.ReadinessStatus
import com.openyt.core.domain.RecoveryAction
import com.openyt.ui.components.OfflineReadinessCard
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.theme.OpenYtTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class OfflineReadinessUiTest(private val scenario: Scenario) {

    @Test
    fun readinessPinningAndCleanupConfirmation() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        var toggled: Pair<String, Boolean>? = null
        var cleaned: Set<String>? = null
        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    OfflineReadinessCard(
                        state = readyState(),
                        onTogglePin = { asset, pinned -> toggled = asset.id to pinned },
                        onCleanup = { cleaned = it },
                        onAction = {},
                        onRetry = {},
                    )
                }
            }
        }

        onNodeWithText(scenario.strings.offline.ready).assertIsDisplayed()
        onNodeWithText(scenario.strings.offline.noMissingItems).assertIsDisplayed()
        onNodeWithText(scenario.strings.offline.unpin).performClick()
        assertEquals("art-1" to false, toggled)

        onNodeWithTag("offline-cleanup").performClick()
        onNodeWithText(scenario.strings.offline.cleanupTitle).assertIsDisplayed()
        onNodeWithText(scenario.strings.cancelAction).performClick()
        assertEquals(null, cleaned)

        onNodeWithTag("offline-cleanup").performClick()
        onNodeWithTag("offline-cleanup-confirm").performClick()
        assertEquals(setOf("sub-1"), cleaned)

        val screenshot = File("build/ui-regressions/offline-${scenario.name}.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", screenshot)
    }

    @Test
    fun missingRequiredArtworkIsNotReadyAndOffersRecovery() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        var action: RecoveryAction? = null
        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    OfflineReadinessCard(
                        state = incompleteState(),
                        onTogglePin = { _, _ -> },
                        onCleanup = {},
                        onAction = { action = it },
                        onRetry = {},
                    )
                }
            }
        }

        onNodeWithText(scenario.strings.offline.incomplete).assertIsDisplayed()
        assertTrue(
            onAllNodesWithText(scenario.strings.offline.ready).fetchSemanticsNodes().isEmpty(),
            "A remote artwork URL must never render the offline-ready badge",
        )
        onNodeWithText(scenario.strings.offline.actionFetchArtwork).performClick()
        assertEquals(RecoveryAction.FETCH_ARTWORK, action)
    }

    private fun readyState(): OfflineLibraryState = OfflineLibraryState(
        entryId = "entry-1",
        readiness = OfflineReadiness(
            entryId = "entry-1",
            status = ReadinessStatus.READY,
            isMediaAccessible = true,
        ),
        localArtworkPath = "/assets/entry-1/art-1.jpg",
        remoteArtworkUrl = "https://remote.example/thumb.jpg",
        assets = listOf(
            OfflineAssetView(
                id = "art-1",
                kind = AssetKind.ARTWORK,
                language = null,
                byteCount = 2048,
                isPinned = true,
                state = AssetState.VERIFIED,
            ),
            OfflineAssetView(
                id = "sub-1",
                kind = AssetKind.SUBTITLE,
                language = "en",
                byteCount = 1024,
                isPinned = false,
                state = AssetState.VERIFIED,
            ),
        ),
        storage = OfflineStorageBreakdown(1000, 3072, 512),
    )

    private fun incompleteState(): OfflineLibraryState = OfflineLibraryState(
        entryId = "entry-1",
        readiness = OfflineReadiness(
            entryId = "entry-1",
            status = ReadinessStatus.INCOMPLETE,
            isMediaAccessible = true,
            missingRequirements = listOf(
                MissingRequirement(
                    kind = AssetKind.ARTWORK,
                    description = "Required artwork pending",
                    suggestedAction = RecoveryAction.FETCH_ARTWORK,
                ),
            ),
        ),
        localArtworkPath = null,
        remoteArtworkUrl = "https://remote.example/thumb.jpg",
        assets = emptyList(),
        storage = OfflineStorageBreakdown(1000, 0, 0),
    )

    data class Scenario(
        val name: String,
        val size: Size,
        val fontScale: Float,
        val dark: Boolean,
        val strings: AppStrings,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios() = listOf(
            arrayOf(Scenario("phone-320-pt", Size(320f, 640f), 1.2f, false, PtBrStrings)),
            arrayOf(Scenario("phone-large-dark-pt", Size(360f, 640f), 1.6f, true, PtBrStrings)),
            arrayOf(Scenario("desktop-en", Size(1024f, 700f), 1f, false, EnStrings)),
        )
    }
}
