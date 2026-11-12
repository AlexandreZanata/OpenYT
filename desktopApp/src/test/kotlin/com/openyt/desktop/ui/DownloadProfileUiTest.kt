package com.openyt.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.MemoryDownloadProfileRepository
import com.openyt.core.domain.ActionableAlternative
import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.IncompatibilityReason
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaFormat
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.OrganizationPreset
import com.openyt.core.domain.ProfileResolution
import com.openyt.core.domain.SubtitleTrack
import com.openyt.ui.components.ProfileEditorDialog
import com.openyt.ui.components.ProfilePlanPreviewCard
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.FeatureText
import com.openyt.ui.i18n.LocalStrings
import com.openyt.ui.i18n.PtBrStrings
import com.openyt.ui.screens.HomeScreen
import com.openyt.ui.theme.OpenYtTheme
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class DownloadProfileUiTest(private val scenario: Scenario) {

    private fun richVideoMedia(): MediaInfo = MediaInfo(
        sourceId = "video123",
        source = "youtube",
        title = "Lição de Arquitetura de Software Multiplataforma",
        webpageUrl = "https://example.com/watch?v=video123",
        durationSeconds = 600,
        author = "OpenYT Academy",
        formats = listOf(
            MediaFormat(
                id = "137",
                kind = MediaKind.VIDEO,
                container = "mp4",
                height = 1080,
                videoCodec = "avc1.640028",
                sizeBytes = 50_000_000L,
            ),
            MediaFormat(
                id = "136",
                kind = MediaKind.VIDEO,
                container = "mp4",
                height = 720,
                videoCodec = "avc1.4d401f",
                sizeBytes = 25_000_000L,
            ),
            MediaFormat(
                id = "135",
                kind = MediaKind.VIDEO,
                container = "mp4",
                height = 480,
                videoCodec = "avc1.4d401e",
                sizeBytes = 12_000_000L,
            ),
            MediaFormat(
                id = "140",
                kind = MediaKind.AUDIO,
                container = "m4a",
                audioCodec = "mp4a.40.2",
                bitrateKbps = 128.0,
                sizeBytes = 5_000_000L,
            ),
        ),
        subtitles = listOf(
            SubtitleTrack(language = "pt", name = "Português", automatic = false),
            SubtitleTrack(language = "en", name = "English", automatic = false),
        ),
    )

    private fun audioOnlyMedia(): MediaInfo = MediaInfo(
        sourceId = "audio999",
        source = "soundcloud",
        title = "Podcast Episódio 42 - Arquitetura Limpa",
        webpageUrl = "https://example.com/podcast/42",
        durationSeconds = 1800,
        author = "OpenYT Audio",
        formats = listOf(
            MediaFormat(
                id = "audio-aac",
                kind = MediaKind.AUDIO,
                container = "m4a",
                audioCodec = "aac",
                bitrateKbps = 256.0,
                sizeBytes = 30_000_000L,
            ),
        ),
    )

    @Test
    fun previewCardRendersAcrossDeviceWidthsAndFontScales() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        val testEngine = MockProfileEngine(richVideoMedia())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DownloadCoordinator(testEngine, scope)
        val profileRepo = MemoryDownloadProfileRepository()

        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    HomeScreen(
                        engine = testEngine,
                        coordinator = coordinator,
                        downloadsDirectory = "/downloads/openyt",
                        onDownloadStarted = {},
                        organizationPreset = OrganizationPreset.PLAYLIST,
                        initialSharedUrl = "https://example.com/watch?v=video123",
                        profileRepository = profileRepo,
                        systemLanguageTag = if (scenario.strings == PtBrStrings) "pt-BR" else "en-US",
                    )
                }
            }
        }

        // Wait until media inspection is complete and chips appear
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(scenario.strings.features[FeatureText.PROFILE_STUDY]).fetchSemanticsNodes().isNotEmpty()
        }

        // Profile selection chips should be present
        onAllNodesWithText(scenario.strings.features[FeatureText.PROFILE_STUDY]).onFirst().performScrollTo().assertIsDisplayed()
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_AUDIO]).performScrollTo().assertIsDisplayed()
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_SAVE_SPACE]).performScrollTo().assertIsDisplayed()
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_TV]).performScrollTo().assertIsDisplayed()
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_MANUAL]).performScrollTo().assertIsDisplayed()

        // Preview card should display destination folder and explicit download button
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_DESTINATION], substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        onNodeWithText(scenario.strings.features[FeatureText.DOWNLOAD_WITH_PROFILE])
            .performScrollTo()
            .assertIsDisplayed()

        // Capture screenshot for visual regression check
        val screenshot = File("build/ui-regressions/profile-preview-${scenario.name}.png")
        screenshot.parentFile.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", screenshot)
        assertTrue(screenshot.exists() && screenshot.length() > 0)
    }

    @Test
    fun changingProfileDoesNotStartDownload() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        val testEngine = MockProfileEngine(richVideoMedia())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DownloadCoordinator(testEngine, scope)
        val profileRepo = MemoryDownloadProfileRepository()

        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    HomeScreen(
                        engine = testEngine,
                        coordinator = coordinator,
                        downloadsDirectory = "/downloads/openyt",
                        onDownloadStarted = {},
                        initialSharedUrl = "https://example.com/watch?v=video123",
                        profileRepository = profileRepo,
                        systemLanguageTag = if (scenario.strings == PtBrStrings) "pt-BR" else "en-US",
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(scenario.strings.features[FeatureText.PROFILE_STUDY]).fetchSemanticsNodes().isNotEmpty()
        }

        // Initially zero download tasks enqueued
        assertEquals(0, coordinator.tasks.value.size, "No tasks should be enqueued on initial display")

        // Switch to Audio profile
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_AUDIO]).performClick()
        assertEquals(0, coordinator.tasks.value.size, "Switching to Audio profile must NOT start download")

        // Switch to Save space profile
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_SAVE_SPACE]).performClick()
        assertEquals(0, coordinator.tasks.value.size, "Switching to Save space profile must NOT start download")

        // Switch to Watch on TV profile
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_TV]).performClick()
        assertEquals(0, coordinator.tasks.value.size, "Switching to TV profile must NOT start download")

        // Switch to Manual selection
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_MANUAL]).performClick()
        assertEquals(0, coordinator.tasks.value.size, "Switching to Manual selection must NOT start download")
    }

    @Test
    fun explicitDownloadActionEnqueuesApprovedPlanSnapshot() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        val testEngine = MockProfileEngine(richVideoMedia())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DownloadCoordinator(testEngine, scope)
        val profileRepo = MemoryDownloadProfileRepository()
        var started = false

        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    HomeScreen(
                        engine = testEngine,
                        coordinator = coordinator,
                        downloadsDirectory = "/downloads/openyt",
                        onDownloadStarted = { started = true },
                        initialSharedUrl = "https://example.com/watch?v=video123",
                        profileRepository = profileRepo,
                        systemLanguageTag = if (scenario.strings == PtBrStrings) "pt-BR" else "en-US",
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(scenario.strings.features[FeatureText.DOWNLOAD_WITH_PROFILE]).fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(0, coordinator.tasks.value.size)

        // Explicitly click "Baixar com perfil" / "Download with profile"
        onNodeWithText(scenario.strings.features[FeatureText.DOWNLOAD_WITH_PROFILE]).performScrollTo().performClick()

        // Exactly one task enqueued and started callback fired
        assertTrue(started, "Download started callback should be invoked")
        assertEquals(1, coordinator.tasks.value.size, "Exactly one task must be enqueued")

        val enqueuedTask = coordinator.tasks.value.first()
        val provenance = enqueuedTask.request.options.profileProvenance
        assertNotNull(provenance, "Profile provenance snapshot must be attached")
        assertEquals(DownloadProfile.BUILTIN_STUDY, provenance.profileId)
        assertEquals("mp4", provenance.resolvedContainer)
        assertEquals("137+140", provenance.selectedFormatId)
    }

    @Test
    fun unavailableOptionsAreActionableNotDeadButtons() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        var switchedProfileId: String? = null
        val incompatibleResolution = ProfileResolution.Incompatible(
            profile = DownloadProfile.WatchOnTv,
            reason = IncompatibilityReason.NO_FORMATS_AVAILABLE,
            explanation = "Fonte não possui faixas de vídeo",
            actionableAlternative = ActionableAlternative.SWITCH_TO_AUDIO,
        )

        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    Box(Modifier.fillMaxSize()) {
                        ProfilePlanPreviewCard(
                            resolution = incompatibleResolution,
                            downloadsDirectory = "/downloads/openyt",
                            onDownloadPlan = {},
                            onSelectProfile = { switchedProfileId = it },
                        )
                    }
                }
            }
        }

        // Reason is displayed
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_INCOMPATIBLE]).assertIsDisplayed()
        onNodeWithText("Fonte não possui faixas de vídeo").assertIsDisplayed()

        // Actionable button is displayed (not a dead disabled button!)
        val switchAudioLabel = scenario.strings.features[FeatureText.ACTION_SWITCH_TO_AUDIO]
        onNodeWithText(switchAudioLabel).assertIsDisplayed().assertIsEnabled()

        // Clicking the actionable button invokes switch callback
        onNodeWithText(switchAudioLabel).performClick()
        assertEquals(DownloadProfile.BUILTIN_AUDIO, switchedProfileId)
    }

    @Test
    fun customProfileEditorDialogValidatesNameAndOptions() = runSkikoComposeUiTest(
        size = scenario.size,
        density = Density(1f, fontScale = scenario.fontScale),
    ) {
        var savedProfile: DownloadProfile? = null
        var dismissed = false

        setContent {
            OpenYtTheme(darkTheme = scenario.dark) {
                CompositionLocalProvider(LocalStrings provides scenario.strings) {
                    ProfileEditorDialog(
                        profile = null,
                        onSave = { savedProfile = it },
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }

        val saveText = scenario.strings.features[FeatureText.SAVE_PROFILE]

        // Dialog title for new profile
        onNodeWithText(scenario.strings.features[FeatureText.ADD_PROFILE]).assertIsDisplayed()

        // Save button is initially disabled because profile name is blank
        onNodeWithText(saveText).assertIsNotEnabled()

        // Type a valid profile name
        onNodeWithText(scenario.strings.features[FeatureText.PROFILE_NAME]).performTextInput("Meu Perfil 720p")

        // Save button is now enabled
        onNodeWithText(saveText).assertIsEnabled()

        // Select 720p max height
        onNodeWithText("720p").performClick()

        // Click Save
        onNodeWithText(saveText).performClick()

        assertNotNull(savedProfile, "Profile must be saved")
        assertEquals("Meu Perfil 720p", savedProfile.name)
        assertEquals(MediaKind.VIDEO, savedProfile.kind)
        assertEquals(720, savedProfile.maxHeight)
    }

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
            arrayOf(Scenario("mobile-light-pt", Size(360f, 640f), 1f, false, PtBrStrings)),
            arrayOf(Scenario("mobile-large-dark-pt", Size(320f, 640f), 1.5f, true, PtBrStrings)),
            arrayOf(Scenario("desktop-dark-pt", Size(1280f, 800f), 1f, true, PtBrStrings)),
            arrayOf(Scenario("desktop-light-en", Size(1024f, 600f), 1.3f, false, EnStrings)),
        )
    }
}

private class MockProfileEngine(private val media: MediaInfo) : DownloadEngine {
    override suspend fun inspect(url: String): EngineResult<Inspection> =
        EngineResult.Success(Inspection.Single(media))

    override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Starting)
        emit(DownloadEvent.Progress(percent = 50.0, speedBytesPerSecond = 2_000_000, etaSeconds = 5))
        emit(DownloadEvent.Completed("/downloads/openyt/output.mp4", 50_000_000L))
    }

    override suspend fun cancel(downloadId: String) = Unit

    override suspend fun capabilities(): EngineCapabilities = EngineCapabilities(
        engineName = "mock-profile-engine",
        engineVersion = "1.0",
        supportsPause = true,
        supportsSubtitles = true,
    )

    override fun close() = Unit
}
