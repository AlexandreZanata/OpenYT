package com.openyt.desktop.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.openyt.core.application.*
import com.openyt.core.automation.*
import com.openyt.core.domain.*
import com.openyt.ui.components.BatchClipsDialog
import com.openyt.ui.components.SubscriptionsDialog
import com.openyt.ui.i18n.*
import com.openyt.ui.theme.OpenYtTheme
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class ExpansionM4UiTest {
    private class Rules : SubscriptionRepository {
        var rule = SubscriptionRule("rule", "Study feed", "https://example.com/feed", SubscriptionKind.FEED, initialized = true,
            candidates = listOf(SubscriptionCandidate(FeedEpisode("old", "Old episode", "https://example.com/old"), CandidateState.FILTERED, SubscriptionReason.DURATION_FILTER)))
        override suspend fun load() = listOf(rule)
        override suspend fun save(rule: SubscriptionRule) { this.rule = rule }
        override suspend fun delete(id: String) = Unit
    }

    @Test fun previewAndReplayDialogDoNotDownloadUntilExplicitConfirmationAndRefreshProfiles() = runSkikoComposeUiTest(size = Size(900f, 900f)) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = Rules()
            val profiles = MemoryDownloadProfileRepository()
            val coordinator = DownloadCoordinator(SuspendedDownloadEngine(), scope)
            val controller = SubscriptionController(repository, FeedReader { listOf(repository.rule.candidates.single().episode) }, SuspendedDownloadEngine(), coordinator,
                { "/tmp" }, { 1 }, { "new" }, scope, profileRepository = profiles)
            runBlocking { controller.restore(); profiles.saveCustomProfile(DownloadProfile.study().copy(id = "custom", name = "New custom profile", isBuiltIn = false)) }
            val before = repository.rule
            setContent { CompositionLocalProvider(LocalStrings provides EnStrings) { OpenYtTheme { Surface { SubscriptionsDialog(controller) {} } } } }
            waitUntil { controller.profiles.value.any { it.id == "custom" } }
            onNodeWithText(EnStrings.features[AutomationText.HISTORY]).performScrollTo().performClick()
            waitUntil { controller.previews.value["rule"]?.isNotEmpty() == true }
            assertEquals(before, repository.rule)
            assertTrue(coordinator.tasks.value.isEmpty())
            onNodeWithText(EnStrings.features[AutomationText.RETRY]).performScrollTo().performClick()
            onNodeWithText(EnStrings.features[AutomationText.APPROVE_REPLAY]).assertIsDisplayed()
            assertEquals(before, repository.rule)
            assertTrue(coordinator.tasks.value.isEmpty())
            onNodeWithText(EnStrings.features[AutomationText.DOWNLOAD_EPISODE]).performClick()
            waitUntil { coordinator.tasks.value.size == 1 }
            assertEquals("rule:old", coordinator.tasks.value.single().request.options.originKey)
        } finally { scope.cancel() }
    }

    private class Processor(private val repository: DownloadRepository) : MediaProcessor {
        val jobs = mutableMapOf<String, ClipJobRecord>()
        val calls = mutableListOf<String>()
        override suspend fun capabilities() = MediaProcessingCapabilities(preciseClips = true, portrait = true, durableBatches = true)
        override suspend fun clipJobs(entryId: String) = jobs.values.toList()
        override suspend fun queueClips(requests: List<MediaProcessingRequest>): Boolean {
            requests.forEach { jobs.putIfAbsent(it.jobId!!, ClipJobRecord(it.jobId!!, it, "sha", ClipJobStatus.QUEUED)) }
            return true
        }
        override suspend fun process(request: MediaProcessingRequest): EngineResult<ProcessedMedia> {
            val id = request.jobId!!
            calls += id
            if (request.segment!!.startMillis == 2_000L && calls.count { it == id } == 1) {
                jobs[id] = jobs[id]!!.copy(status = ClipJobStatus.FAILED)
                return EngineResult.Failure(DownloadFailure.ProcessingFailed(null))
            }
            val output = ProcessedMedia("processed-$id", "Clip", "/fixture/$id.mp4", MediaKind.VIDEO, 100)
            repository.insertLibraryEntry(LibraryEntry(output.id, output.title, output.kind, output.path, author = "Source author"))
            jobs[id] = jobs[id]!!.copy(status = ClipJobStatus.SUCCEEDED, output = output)
            return EngineResult.Success(output)
        }
    }

    @Test fun batchKeepsPartialSuccessAndRetryOnlyProcessesFailedClips() = runSkikoComposeUiTest(size = Size(960f, 900f)) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val root = java.nio.file.Files.createTempDirectory("openyt-batch-ui-").toFile()
        try {
            val repository = com.openyt.data.SqlDelightDownloadRepository(com.openyt.data.createOpenYtDatabase(root.resolve("library.db").path))
            val processor = Processor(repository)
            val coordinator = DownloadCoordinator(SuspendedDownloadEngine(), scope, repository = repository)
            setContent { CompositionLocalProvider(LocalStrings provides EnStrings) { OpenYtTheme { Surface {
                BatchClipsDialog(LibraryEntry("entry", "Fixture", MediaKind.VIDEO, "/fixture/source.mp4"), processor, coordinator,
                    MediaProbe { MediaTechnicalInfo(durationMillis = 10_000) }, null, null, null) {}
            } } } }
            waitForIdle()
            onNodeWithText(EnStrings.features[FeatureText.END_SECONDS]).performTextReplacement("2")
            onNodeWithText(EnStrings.features[ClipText.ADD]).performScrollTo().performClick()
            waitForIdle()
            onNodeWithText(EnStrings.features[FeatureText.START_SECONDS]).performScrollTo().performTextReplacement("2")
            onNodeWithText(EnStrings.features[FeatureText.END_SECONDS]).performTextReplacement("4")
            onNodeWithText(EnStrings.features[ClipText.ADD]).performScrollTo().performClick()
            onNodeWithText(EnStrings.features[ClipText.EXPORT]).performClick()
            waitUntil { processor.jobs.values.count { it.status == ClipJobStatus.FAILED } == 1 }
            assertEquals(1, coordinator.library.value.size)
            onNodeWithText(EnStrings.features[FeatureText.START_SECONDS]).performScrollTo()
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File("build/ui-regressions/batch-clips-desktop-en.png").apply { parentFile.mkdirs() })
            val successId = processor.jobs.values.single { it.status == ClipJobStatus.SUCCEEDED }.id
            onNodeWithText(EnStrings.features[ClipText.EXPORT]).performClick()
            waitUntil { coordinator.library.value.size == 2 }
            assertTrue(coordinator.library.value.all { it.author == "Source author" })
            assertEquals(1, processor.calls.count { it == successId })
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun unavailableCapabilitiesStayDisabledOnNarrowPortugueseLargeTextUi() = runSkikoComposeUiTest(size = Size(320f, 800f), density = Density(1f, 2f)) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val processor = object : MediaProcessor {
                override suspend fun process(request: MediaProcessingRequest): EngineResult<ProcessedMedia> = error("Disabled control must not process")
            }
            setContent { CompositionLocalProvider(LocalStrings provides PtBrStrings) { OpenYtTheme { Surface {
                BatchClipsDialog(LibraryEntry("entry", "Vídeo", MediaKind.VIDEO, "/fixture.mp4"), processor, DownloadCoordinator(SuspendedDownloadEngine(), scope), MediaProbe { MediaTechnicalInfo(durationMillis = 10_000) }, null, null, null) {}
            } } } }
            waitForIdle()
            onNodeWithText(PtBrStrings.features[ClipText.PRECISE]).assertIsNotEnabled()
            onNodeWithText(PtBrStrings.features[ClipText.ADD]).performScrollTo().assertIsNotEnabled()
            onNodeWithText(PtBrStrings.closeAction).assertIsDisplayed()
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File("build/ui-regressions/batch-clips-phone-large-pt.png").apply { parentFile.mkdirs() })
        } finally { scope.cancel() }
    }
}
