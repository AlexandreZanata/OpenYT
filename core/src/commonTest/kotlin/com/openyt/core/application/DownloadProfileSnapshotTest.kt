package com.openyt.core.application

import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.DownloadProfileResolver
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaFormat
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.PlanRevalidationResult
import com.openyt.core.domain.ProfileProvenance
import com.openyt.core.domain.ProfileResolution
import com.openyt.core.domain.SmartCollection
import com.openyt.core.domain.revalidatePlan
import com.openyt.core.domain.toDownloadRequest
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProfileSnapshotTest {

    private fun kotlinx.coroutines.test.TestScope.coordinatorScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob())

    @Test
    fun editingProfileAfterEnqueueDoesNotMutateQueuedRequest() = runTest {
        val repo = InMemoryDownloadRepository()
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repo)
        coordinator.restore()

        var customProfile = DownloadProfile(
            id = "custom-study-1",
            name = "My Study Profile",
            isBuiltIn = false,
            kind = MediaKind.VIDEO,
            maxHeight = 1080,
        )
        val media = sampleMediaInfo()
        val resolution = DownloadProfileResolver.resolve(customProfile, media) as ProfileResolution.Success
        val plan = resolution.plan

        val taskId = coordinator.enqueuePlan(
            media = media,
            plan = plan,
            destinationDirectory = "/local/study",
            exportTargetUri = "content://tree/study-export",
        )
        coordinator.awaitPersistence()

        // Edit custom profile later
        customProfile = customProfile.copy(
            name = "Edited Profile Name",
            maxHeight = 480,
        )

        // Queued task must retain original snapshot and provenance
        val queuedTask = coordinator.tasks.value.single { it.id == taskId }
        assertEquals("My Study Profile", queuedTask.request.options.profileProvenance?.profileName)
        assertEquals(1080, queuedTask.request.options.profileProvenance?.effectiveHeight)
        assertEquals("/local/study", queuedTask.request.destinationDirectory)
        assertEquals("content://tree/study-export", queuedTask.request.options.exportTargetUri)
    }

    @Test
    fun deletingProfileAfterEnqueueDoesNotMutateQueuedRequest() = runTest {
        val repo = InMemoryDownloadRepository()
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repo)
        coordinator.restore()

        val profile = DownloadProfile(
            id = "temporary-profile",
            name = "Temp Profile",
            isBuiltIn = false,
            kind = MediaKind.VIDEO,
        )
        val media = sampleMediaInfo()
        val plan = (DownloadProfileResolver.resolve(profile, media) as ProfileResolution.Success).plan

        val taskId = coordinator.enqueuePlan(
            media = media,
            plan = plan,
            destinationDirectory = "/local/downloads",
        )
        coordinator.awaitPersistence()

        // Simulate deleting profile (profile no longer exists in repository)
        val queuedTask = coordinator.tasks.value.single { it.id == taskId }
        assertEquals("temporary-profile", queuedTask.request.options.profileProvenance?.profileId)
        assertEquals("Temp Profile", queuedTask.request.options.profileProvenance?.profileName)
        assertFalse(queuedTask.status == DownloadStatus.CANCELED)
    }

    @Test
    fun changingGlobalDestinationAfterEnqueueDoesNotMutateQueuedRequest() = runTest {
        var globalDestination = "/global/downloads"
        var globalTreeUri: String? = "content://tree/global"

        val repo = InMemoryDownloadRepository()
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repo)
        coordinator.restore()

        val media = sampleMediaInfo()
        val plan = (DownloadProfileResolver.resolve(DownloadProfile.AudioOnly, media) as ProfileResolution.Success).plan

        val taskId = coordinator.enqueuePlan(
            media = media,
            plan = plan,
            destinationDirectory = "/specific/audio/path",
            exportTargetUri = "content://tree/specific-music",
        )
        coordinator.awaitPersistence()

        // Mutate global settings
        globalDestination = "/new/global/path"
        globalTreeUri = "content://tree/new-global"

        // Queued request must remain unchanged
        val task = coordinator.tasks.value.single { it.id == taskId }
        assertEquals("/specific/audio/path", task.request.destinationDirectory)
        assertEquals("content://tree/specific-music", task.request.options.exportTargetUri)
    }

    @Test
    fun twoTasksWithDifferentDestinationTargetsRemainIndependent() = runTest {
        val repo = InMemoryDownloadRepository()
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repo)
        coordinator.restore()

        val media1 = sampleMediaInfo("video-1", "https://example.com/v1")
        val media2 = sampleMediaInfo("video-2", "https://example.com/v2")

        val plan1 = (DownloadProfileResolver.resolve(DownloadProfile.AudioOnly, media1) as ProfileResolution.Success).plan
        val plan2 = (DownloadProfileResolver.resolve(DownloadProfile.WatchOnTv, media2) as ProfileResolution.Success).plan

        val id1 = coordinator.enqueuePlan(media1, plan1, "/storage/audio", exportTargetUri = null)
        val id2 = coordinator.enqueuePlan(media2, plan2, "/storage/tv", exportTargetUri = "content://tree/tv-box")
        coordinator.awaitPersistence()

        val task1 = coordinator.tasks.value.single { it.id == id1 }
        val task2 = coordinator.tasks.value.single { it.id == id2 }

        assertEquals("/storage/audio", task1.request.destinationDirectory)
        assertNull(task1.request.options.exportTargetUri)
        assertEquals(DownloadProfile.BUILTIN_AUDIO, task1.request.options.profileProvenance?.profileId)

        assertEquals("/storage/tv", task2.request.destinationDirectory)
        assertEquals("content://tree/tv-box", task2.request.options.exportTargetUri)
        assertEquals(DownloadProfile.BUILTIN_TV, task2.request.options.profileProvenance?.profileId)
    }

    @Test
    fun restartPreservesSnapshotAndProvenance() = runTest {
        val repo = InMemoryDownloadRepository()
        val coordinator1 = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repo)
        coordinator1.restore()

        val media = sampleMediaInfo()
        val plan = (DownloadProfileResolver.resolve(DownloadProfile.SaveSpace, media) as ProfileResolution.Success).plan

        val id = coordinator1.enqueuePlan(
            media = media,
            plan = plan,
            destinationDirectory = "/data/local/save-space",
            exportTargetUri = "content://tree/external-sd",
        )
        coordinator1.awaitPersistence()

        // Restart coordinator with same repository
        val coordinator2 = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repo)
        coordinator2.restore()

        val restoredTask = coordinator2.tasks.value.single { it.id == id }
        val provenance = restoredTask.request.options.profileProvenance
        assertNotNull(provenance)
        assertEquals(DownloadProfile.BUILTIN_SAVE_SPACE, provenance.profileId)
        assertEquals("Save space", provenance.profileName)
        assertTrue(provenance.isBuiltIn)
        assertEquals("mp4", provenance.resolvedContainer)
        assertEquals(480, provenance.effectiveHeight)
        assertEquals("content://tree/external-sd", restoredTask.request.options.exportTargetUri)
    }

    @Test
    fun legacyTasksWithoutProvenanceRetainExistingBehavior() = runTest {
        val legacyTask = DownloadTask(
            id = "legacy-1",
            media = sampleMediaInfo(),
            request = DownloadRequest(
                url = "https://example.com/watch",
                destinationDirectory = "/legacy/dir",
                options = DownloadOptions(),
            ),
        )
        val repo = InMemoryDownloadRepository(listOf(legacyTask))
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repo)
        coordinator.restore()

        val task = coordinator.tasks.value.single { it.id == "legacy-1" }
        assertNull(task.request.options.profileProvenance)
        assertNull(task.request.options.exportTargetUri)
        assertEquals("/legacy/dir", task.request.destinationDirectory)
    }

    @Test
    fun safUriNeverEntersFilesystemDestinationField() {
        val media = sampleMediaInfo()
        val plan = (DownloadProfileResolver.resolve(DownloadProfile.AudioOnly, media) as ProfileResolution.Success).plan

        assertFailsWith<IllegalArgumentException> {
            plan.toDownloadRequest(
                url = "https://example.com/item",
                destinationDirectory = "content://com.android.externalstorage.documents/tree/123",
            )
        }
    }

    @Test
    fun retryPreservesSavedSnapshotAndChoices() = runTest {
        val repo = InMemoryDownloadRepository()
        val engine = ImmediateEngine(
            listOf(DownloadEvent.Starting, DownloadEvent.Failed(DownloadFailure.Network)),
        )
        val coordinator = DownloadCoordinator(engine, coordinatorScope(), repo)
        coordinator.restore()

        val media = sampleMediaInfo()
        val plan = (DownloadProfileResolver.resolve(DownloadProfile.study(), media) as ProfileResolution.Success).plan

        val id = coordinator.enqueuePlan(
            media = media,
            plan = plan,
            destinationDirectory = "/local/study",
            exportTargetUri = "content://tree/study",
        )
        coordinator.awaitPersistence()
        testScheduler.advanceUntilIdle()

        // Verify task failed as expected
        val failedTask = coordinator.tasks.value.single { it.id == id }
        assertEquals(DownloadStatus.FAILED, failedTask.status)

        // Retry the task
        coordinator.retry(id)
        coordinator.awaitPersistence()

        val tasks = coordinator.tasks.value
        assertEquals(2, tasks.size)
        val retriedTask = tasks.last()

        val expectedRequest = plan.toDownloadRequest(media.webpageUrl, "/local/study", "content://tree/study")
        assertEquals(expectedRequest.formatId, retriedTask.request.formatId)
        assertEquals("/local/study", retriedTask.request.destinationDirectory)
        assertEquals("content://tree/study", retriedTask.request.options.exportTargetUri)
        assertEquals(DownloadProfile.BUILTIN_STUDY, retriedTask.request.options.profileProvenance?.profileId)
    }

    @Test
    fun revokedSafGrantProducesWarningWithoutFailingLocalDownload() = runTest {
        var warningReported: SafExportResult.Warning? = null
        val failingExporter = SafExporter { _, _ -> false }

        val router = SafExportRouter(
            exporter = failingExporter,
            onWarning = { warningReported = it },
        )

        val completedTask = DownloadTask(
            id = "completed-task-1",
            media = sampleMediaInfo(),
            request = DownloadRequest(
                url = "https://example.com/v",
                destinationDirectory = "/local/dir",
                options = DownloadOptions(exportTargetUri = "content://tree/revoked-grant"),
            ),
            status = DownloadStatus.COMPLETED,
            filePath = "/local/dir/video.mp4",
        )

        val result = router.routeExport(completedTask)
        assertTrue(result is SafExportResult.Warning)
        assertTrue(result.message.contains("SAF copy-export failed or tree grant was revoked"))
        assertEquals("/local/dir/video.mp4", result.filePath)
        assertEquals("content://tree/revoked-grant", result.treeUri)

        // Router callback also invoked
        router.onCompleted(completedTask)
        assertNotNull(warningReported)
        assertEquals(completedTask.filePath, warningReported.filePath)

        // Local task status is still COMPLETED
        assertEquals(DownloadStatus.COMPLETED, completedTask.status)
    }

    @Test
    fun expiredSourceFormatRevalidationRequiresApproval() {
        val originalMedia = sampleMediaInfo(
            formats = listOf(
                MediaFormat("137", MediaKind.VIDEO, "mp4", height = 1080),
                MediaFormat("140", MediaKind.AUDIO, "m4a"),
            ),
        )
        val plan = (DownloadProfileResolver.resolve(DownloadProfile.study(), originalMedia) as ProfileResolution.Success).plan
        val request = plan.toDownloadRequest(originalMedia.webpageUrl, "/tmp")
        val task = DownloadTask("task-1", originalMedia, request)

        // Fresh media with formats still present -> Valid
        val freshValidMedia = sampleMediaInfo(
            formats = listOf(
                MediaFormat("137", MediaKind.VIDEO, "mp4", height = 1080),
                MediaFormat("140", MediaKind.AUDIO, "m4a"),
            ),
        )
        val validResult = revalidatePlan(task, freshValidMedia)
        assertTrue(validResult is PlanRevalidationResult.Valid)

        // Fresh media where format "137" expired and only "136" (720p) is present
        val freshExpiredMedia = sampleMediaInfo(
            formats = listOf(
                MediaFormat("136", MediaKind.VIDEO, "mp4", height = 720),
                MediaFormat("140", MediaKind.AUDIO, "m4a"),
            ),
        )
        val revalidation = revalidatePlan(task, freshExpiredMedia)
        assertTrue(revalidation is PlanRevalidationResult.RequiresApproval)
        assertEquals("136+140", revalidation.proposedPlan.toDownloadRequest(freshExpiredMedia.webpageUrl, "/tmp").formatId)
    }

    @Test
    fun noCredentialsInSnapshotOrDiagnostics() {
        val provenance = ProfileProvenance(
            profileId = "my-profile",
            profileName = "Clean Profile",
            isBuiltIn = false,
            resolvedContainer = "mp4",
            effectiveHeight = 1080,
            selectedFormatId = "137+140",
            sourceWebpageUrl = "https://example.com/video",
        )
        val options = DownloadOptions(
            exportTargetUri = "content://tree/target",
            profileProvenance = provenance,
        )

        val json = Json.encodeToString(options)
        assertFalse(json.contains("cookie", ignoreCase = true))
        assertFalse(json.contains("token", ignoreCase = true))
        assertFalse(json.contains("password", ignoreCase = true))
        assertFalse(json.contains("auth", ignoreCase = true))
    }

    private fun sampleMediaInfo(
        id: String = "sample-1",
        webpageUrl: String = "https://example.com/watch?v=1",
        formats: List<MediaFormat> = listOf(
            MediaFormat("137", MediaKind.VIDEO, "mp4", videoCodec = "avc1.640028", height = 1080),
            MediaFormat("135", MediaKind.VIDEO, "mp4", videoCodec = "avc1.4d401f", height = 480),
            MediaFormat("140", MediaKind.AUDIO, "m4a", audioCodec = "mp4a.40.2"),
        ),
    ): MediaInfo = MediaInfo(
        sourceId = id,
        source = "youtube",
        title = "Sample Title",
        webpageUrl = webpageUrl,
        formats = formats,
    )

    private class ImmediateEngine(
        private val events: List<DownloadEvent>,
    ) : com.openyt.core.domain.DownloadEngine {
        override suspend fun inspect(url: String): com.openyt.core.domain.EngineResult<com.openyt.core.domain.Inspection> =
            com.openyt.core.domain.EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(
            request: DownloadRequest,
            downloadId: String,
        ): kotlinx.coroutines.flow.Flow<DownloadEvent> = kotlinx.coroutines.flow.flow {
            events.forEach { emit(it) }
        }

        override suspend fun cancel(downloadId: String) = Unit
        override suspend fun capabilities(): com.openyt.core.domain.EngineCapabilities =
            com.openyt.core.domain.EngineCapabilities("test", "0", supportsPause = false, supportsSubtitles = false)
        override fun close() = Unit
    }

    private class InMemoryDownloadRepository(
        initialTasks: List<DownloadTask> = emptyList(),
    ) : DownloadRepository {
        private val tasks = mutableMapOf<String, DownloadTask>().apply {
            initialTasks.forEach { put(it.id, it) }
        }
        val library = mutableListOf<LibraryEntry>()

        override suspend fun loadTasks(): List<DownloadTask> = tasks.values.toList()
        override suspend fun insertTask(task: DownloadTask) { tasks[task.id] = task }
        override suspend fun updateTask(task: DownloadTask) { tasks[task.id] = task }
        override suspend fun deleteTask(id: String) { tasks.remove(id) }

        override suspend fun markActiveInterrupted() {
            tasks.replaceAll { _, task ->
                if (task.status.isActive) task.copy(status = DownloadStatus.INTERRUPTED) else task
            }
        }

        override suspend fun loadCollections(): List<Collection> = emptyList()
        override suspend fun insertCollection(collection: Collection) = Unit
        override suspend fun deleteCollection(id: String) = Unit
        override suspend fun loadCollectionMembership(): Map<String, Set<String>> = emptyMap()
        override suspend fun addEntryToCollection(collectionId: String, entryId: String) = Unit
        override suspend fun removeEntryFromCollection(collectionId: String, entryId: String) = Unit
        override suspend fun loadSmartCollections(): List<SmartCollection> = emptyList()
        override suspend fun insertSmartCollection(collection: SmartCollection) = Unit
        override suspend fun deleteSmartCollection(id: String) = Unit
        override suspend fun loadLibrary(): List<LibraryEntry> = library
        override suspend fun insertLibraryEntry(entry: LibraryEntry) { library.add(entry) }
        override suspend fun updateLibraryPath(id: String, filePath: String) = Unit
        override suspend fun updateLibraryMetadata(id: String, title: String, author: String?) = Unit
        override suspend fun deleteLibraryEntry(id: String) { library.removeAll { it.id == id } }
        override suspend fun addLibraryTag(entryId: String, tag: String) = Unit
        override suspend fun removeLibraryTag(entryId: String, tag: String) = Unit
    }
}
