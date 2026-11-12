package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.Collection
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.OfflineReadiness
import com.openyt.core.domain.ReadinessStatus
import com.openyt.core.domain.RecoveryAction
import com.openyt.core.domain.SmartCollection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionArtifactConfirmationTest {

    private class FakeMediaFileInspector : MediaFileInspector {
        val files = mutableMapOf<String, FakeFile>()

        data class FakeFile(
            var sizeBytes: Long,
            var lastModifiedEpochMillis: Long = 1000L,
            var sha256: String = "test-hash",
            var readable: Boolean = true,
        )

        fun addFile(path: String, sizeBytes: Long, lastModified: Long = 1000L, sha256: String = "hash-$path") {
            files[path] = FakeFile(sizeBytes, lastModified, sha256)
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun inspect(path: String): FileMetadata? {
            val file = files[path] ?: return null
            if (!file.readable) return null
            return FileMetadata(file.sizeBytes, file.lastModifiedEpochMillis)
        }

        override suspend fun computeSha256(path: String): String? {
            val file = files[path] ?: return null
            if (!file.readable) return null
            return file.sha256
        }
    }

    private class FakeDownloadRepository(
        var tasks: MutableList<DownloadTask> = mutableListOf(),
        var library: MutableList<LibraryEntry> = mutableListOf(),
    ) : DownloadRepository {
        override suspend fun loadTasks(): List<DownloadTask> = tasks.toList()

        override suspend fun insertTask(task: DownloadTask) {
            tasks.removeAll { it.id == task.id }
            tasks += task
        }

        override suspend fun updateTask(task: DownloadTask) = insertTask(task)

        override suspend fun deleteTask(id: String) {
            tasks.removeAll { it.id == id }
        }

        override suspend fun markActiveInterrupted() {
            tasks = tasks.map { task ->
                if (task.status.isActive) task.copy(status = DownloadStatus.INTERRUPTED) else task
            }.toMutableList()
        }

        override suspend fun loadLibrary(): List<LibraryEntry> = library.toList()

        override suspend fun insertLibraryEntry(entry: LibraryEntry) {
            library.removeAll { it.id == entry.id }
            library += entry
        }

        override suspend fun deleteLibraryEntry(id: String) {
            library.removeAll { it.id == id }
        }

        override suspend fun updateLibraryPath(id: String, filePath: String) {
            library.replaceAll { entry ->
                if (entry.id == id) entry.copy(filePath = filePath) else entry
            }
        }

        override suspend fun updateLibraryMetadata(id: String, title: String, author: String?) {
            library.replaceAll { entry ->
                if (entry.id == id) entry.copy(title = title, author = author) else entry
            }
        }

        override suspend fun addLibraryTag(entryId: String, tag: String) = Unit
        override suspend fun removeLibraryTag(entryId: String, tag: String) = Unit
        override suspend fun loadCollections(): List<Collection> = emptyList()
        override suspend fun insertCollection(collection: Collection) = Unit
        override suspend fun deleteCollection(id: String) = Unit
        override suspend fun loadCollectionMembership(): Map<String, Set<String>> = emptyMap()
        override suspend fun addEntryToCollection(collectionId: String, entryId: String) = Unit
        override suspend fun removeEntryFromCollection(collectionId: String, entryId: String) = Unit
        override suspend fun loadSmartCollections(): List<SmartCollection> = emptyList()
        override suspend fun insertSmartCollection(collection: SmartCollection) = Unit
        override suspend fun deleteSmartCollection(id: String) = Unit
    }

    private class ControlledEngine(private val eventChannel: Channel<DownloadEvent>) : DownloadEngine {
        override fun download(request: DownloadRequest, downloadId: String) = flow {
            emit(DownloadEvent.Starting)
            for (event in eventChannel) {
                emit(event)
            }
        }
        override suspend fun inspect(url: String): EngineResult<Inspection> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)
        override suspend fun capabilities(): EngineCapabilities = EngineCapabilities(
            engineName = "ControlledEngine",
            engineVersion = "1.0",
            supportsPause = true,
            supportsSubtitles = true,
        )
        override suspend fun cancel(downloadId: String) {}
        override fun close() {}
    }

    private class ThrowingOfflineAssetStore : OfflineAssetStore {
        override suspend fun storeConfirmedArtifact(
            entryId: String,
            receipt: CompanionArtifactReceipt,
            mediaRevision: MediaRevision,
            allowedRoots: List<String>,
        ): Result<OfflineAsset> {
            throw IllegalStateException("Disk I/O failed while storing artifact ${receipt.filePath}")
        }
        override suspend fun getAssetsForEntry(entryId: String): List<OfflineAsset> = emptyList()
        override fun resolveAssetPath(relativeLocator: String): String = "/throwing/$relativeLocator"
        override suspend fun deleteOwnedAsset(asset: OfflineAsset): Boolean = false
    }

    private fun sampleMedia(id: String = "test-media") = MediaInfo(
        sourceId = id,
        source = "youtube",
        title = "Curso Básico de Kotlin",
        author = "Instrutor",
        webpageUrl = "https://example.com/watch?v=$id",
    )

    private fun sampleRequest(dest: String = "/tmp/downloads") = DownloadRequest(
        url = "https://example.com/watch?v=123",
        destinationDirectory = dest,
    )

    // 1. Output path with quotes and Unicode is normalized and validated
    @Test
    fun outputPathWithQuotesAndUnicodeIsNormalizedAndValidated() {
        val inspector = FakeMediaFileInspector()
        val root = "/tmp/openyt/downloads"
        val unicodePath = "/tmp/openyt/downloads/Curso \"Básico\" [Ação] #1.pt.vtt"
        inspector.addFile(unicodePath, sizeBytes = 4096L)

        val receipt = CompanionArtifactReceipt(
            kind = AssetKind.SUBTITLE,
            filePath = unicodePath,
            language = "pt",
        )

        val result = CompanionArtifactValidator.validateReceipt(
            receipt = receipt,
            allowedRoots = listOf(root),
            fileInspector = inspector,
        )

        assertIs<ArtifactValidationResult.Valid>(result)
        assertEquals(unicodePath, result.receipt.filePath)
        assertEquals(4096L, result.sizeBytes)

        // Surrounding quotes stripped
        val quotedPath = "\"$unicodePath\""
        val quotedReceipt = receipt.copy(filePath = quotedPath)
        val quotedResult = CompanionArtifactValidator.validateReceipt(
            receipt = quotedReceipt,
            allowedRoots = listOf(root),
            fileInspector = inspector,
        )
        assertIs<ArtifactValidationResult.Valid>(quotedResult)
        assertEquals(unicodePath, quotedResult.receipt.filePath)

        // Windows path with Unicode and case-insensitivity
        val winRoot = "C:\\OpenYT\\Downloads"
        val winPath = "c:\\openyt\\downloads\\Vídeo \"Aula\" #2.pt.srt"
        assertTrue(CompanionArtifactValidator.isPathWithinRoot(winPath, winRoot))
    }

    // 2. Forged out-of-root receipt is strictly rejected
    @Test
    fun forgedOutOfRootReceiptIsStrictlyRejected() {
        val inspector = FakeMediaFileInspector()
        val root = "/tmp/openyt/downloads"
        inspector.addFile("/etc/passwd", sizeBytes = 1024L)
        inspector.addFile("/tmp/openyt/downloads/../../etc/passwd", sizeBytes = 1024L)
        inspector.addFile("/tmp/openyt/downloads_attacker/evil.vtt", sizeBytes = 1024L)

        val receipts = listOf(
            CompanionArtifactReceipt(AssetKind.SUBTITLE, "/etc/passwd"),
            CompanionArtifactReceipt(AssetKind.SUBTITLE, "/tmp/openyt/downloads/../../etc/passwd"),
            CompanionArtifactReceipt(AssetKind.SUBTITLE, "/tmp/openyt/downloads_attacker/evil.vtt"),
            CompanionArtifactReceipt(AssetKind.SUBTITLE, "../outside.vtt"),
        )

        for (receipt in receipts) {
            val result = CompanionArtifactValidator.validateReceipt(
                receipt = receipt,
                allowedRoots = listOf(root),
                fileInspector = inspector,
            )
            assertTrue(
                result is ArtifactValidationResult.OutOfRoot || result is ArtifactValidationResult.InvalidPath,
                "Expected rejection for forged path: ${receipt.filePath}, but got $result",
            )
        }

        // Windows traversal check
        assertFalse(CompanionArtifactValidator.isPathWithinRoot("C:\\OpenYT\\Downloads\\..\\Windows\\System32\\calc.exe", "C:\\OpenYT\\Downloads"))
        assertFalse(CompanionArtifactValidator.isPathWithinRoot("D:\\OtherDrive\\file.vtt", "C:\\OpenYT\\Downloads"))
    }

    // 3. Absent or empty files are rejected
    @Test
    fun absentOrEmptyFilesAreRejected() {
        val inspector = FakeMediaFileInspector()
        val root = "/tmp/downloads"
        val missingPath = "/tmp/downloads/missing.vtt"
        val emptyPath = "/tmp/downloads/empty.vtt"
        inspector.addFile(emptyPath, sizeBytes = 0L)

        val missingReceipt = CompanionArtifactReceipt(AssetKind.SUBTITLE, missingPath)
        val missingResult = CompanionArtifactValidator.validateReceipt(missingReceipt, listOf(root), inspector)
        assertIs<ArtifactValidationResult.FileMissing>(missingResult)

        val emptyReceipt = CompanionArtifactReceipt(AssetKind.SUBTITLE, emptyPath)
        val emptyResult = CompanionArtifactValidator.validateReceipt(emptyReceipt, listOf(root), inspector)
        assertIs<ArtifactValidationResult.FileEmpty>(emptyResult)
    }

    // 4. Legacy Completed events without artifacts succeed normally
    @Test
    fun legacyCompletedEventsWithoutArtifactsSucceedNormally() = runTest {
        val repository = FakeDownloadRepository()
        val eventChannel = Channel<DownloadEvent>(Channel.UNLIMITED)
        val coordinator = DownloadCoordinator(
            engine = ControlledEngine(eventChannel),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            repository = repository,
        )

        val id = coordinator.enqueue(sampleMedia("legacy-1"), sampleRequest())
        advanceUntilIdle()

        // Emitting legacy Completed event without artifacts parameter
        eventChannel.send(DownloadEvent.Completed(filePath = "/tmp/downloads/legacy.mp4", sizeBytes = 5000L))
        advanceUntilIdle()

        val task = coordinator.tasks.value.first { it.id == id }
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(1, coordinator.library.value.size)
        assertEquals("/tmp/downloads/legacy.mp4", coordinator.library.value.first().filePath)
    }

    // 5. Interrupted or canceled completion never stores artifacts
    @Test
    fun interruptedOrCanceledCompletionNeverStoresArtifacts() = runTest {
        val repository = FakeDownloadRepository()
        val fileInspector = FakeMediaFileInspector()
        val assetStore = MemoryOfflineAssetStore(fileInspector = fileInspector)
        val eventChannel = Channel<DownloadEvent>(Channel.UNLIMITED)
        val coordinator = DownloadCoordinator(
            engine = ControlledEngine(eventChannel),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            repository = repository,
            assetStore = assetStore,
        )

        val id = coordinator.enqueue(sampleMedia("canceled-1"), sampleRequest())
        advanceUntilIdle()

        eventChannel.send(DownloadEvent.Canceled)
        advanceUntilIdle()

        val task = coordinator.tasks.value.first { it.id == id }
        assertEquals(DownloadStatus.CANCELED, task.status)
        assertTrue(coordinator.library.value.isEmpty())
        assertTrue(assetStore.getAssetsForEntry(id).isEmpty())
    }

    // 6. Confirmed artifacts are safely stored via assetStore on completion
    @Test
    fun confirmedArtifactsAreStoredOnCompletion() = runTest {
        val repository = FakeDownloadRepository()
        val fileInspector = FakeMediaFileInspector()
        val subPath = "/tmp/downloads/video.pt.vtt"
        val artPath = "/tmp/downloads/video.jpg"
        fileInspector.addFile(subPath, sizeBytes = 2048L, sha256 = "sub-sha256")
        fileInspector.addFile(artPath, sizeBytes = 8192L, sha256 = "art-sha256")

        val assetStore = MemoryOfflineAssetStore(fileInspector = fileInspector)
        val eventChannel = Channel<DownloadEvent>(Channel.UNLIMITED)
        val coordinator = DownloadCoordinator(
            engine = ControlledEngine(eventChannel),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            repository = repository,
            assetStore = assetStore,
        )

        val id = coordinator.enqueue(sampleMedia("entry-artifacts"), sampleRequest("/tmp/downloads"))
        advanceUntilIdle()

        val artifacts = listOf(
            CompanionArtifactReceipt(AssetKind.SUBTITLE, subPath, language = "pt"),
            CompanionArtifactReceipt(AssetKind.ARTWORK, artPath),
        )
        eventChannel.send(
            DownloadEvent.Completed(
                filePath = "/tmp/downloads/video.mp4",
                sizeBytes = 10000L,
                artifacts = artifacts,
            ),
        )
        advanceUntilIdle()

        val task = coordinator.tasks.value.first { it.id == id }
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(1, coordinator.library.value.size)

        val storedAssets = assetStore.getAssetsForEntry(id)
        assertEquals(2, storedAssets.size)
        assertTrue(storedAssets.any { it.kind == AssetKind.SUBTITLE && it.language == "pt" })
        assertTrue(storedAssets.any { it.kind == AssetKind.ARTWORK })
    }

    // 7. Primary media completion remains truthful even if optional artwork fails
    @Test
    fun primaryMediaCompletionRemainsTruthfulEvenIfOptionalArtworkFails() = runTest {
        val repository = FakeDownloadRepository()
        val throwingStore = ThrowingOfflineAssetStore()
        val eventChannel = Channel<DownloadEvent>(Channel.UNLIMITED)
        val coordinator = DownloadCoordinator(
            engine = ControlledEngine(eventChannel),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            repository = repository,
            assetStore = throwingStore,
        )

        val id = coordinator.enqueue(sampleMedia("truthful-1"), sampleRequest("/tmp/downloads"))
        advanceUntilIdle()

        val artifacts = listOf(
            CompanionArtifactReceipt(AssetKind.ARTWORK, "/tmp/downloads/optional.jpg", isRequired = false),
        )

        // Completed event emitted with optional artwork
        eventChannel.send(
            DownloadEvent.Completed(
                filePath = "/tmp/downloads/truthful.mp4",
                sizeBytes = 12345L,
                artifacts = artifacts,
            ),
        )
        advanceUntilIdle()

        // Primary media must be preserved in library and marked completed truthfully!
        val task = coordinator.tasks.value.first { it.id == id }
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(1, coordinator.library.value.size)
        assertEquals("/tmp/downloads/truthful.mp4", coordinator.library.value.first().filePath)
        assertEquals(12345L, coordinator.library.value.first().sizeBytes)
    }

    // 8. Missing required asset leaves offline readiness incomplete with retry available
    @Test
    fun missingRequiredAssetLeavesReadinessIncompleteWithRetry() {
        val mediaRev = MediaRevision(sizeBytes = 50000L, lastModifiedEpochMillis = 1000L, contentSha256 = "aula-hash")
        val entry = LibraryEntry(
            id = "study-entry",
            title = "Aula Importante",
            kind = MediaKind.VIDEO,
            filePath = "/tmp/downloads/aula.mp4",
            sizeBytes = 50000L,
            mediaRevision = mediaRev,
        )
        val inspector = FakeMediaFileInspector()
        inspector.addFile("/tmp/downloads/aula.mp4", sizeBytes = 50000L, sha256 = "aula-hash")

        val requiredArtwork = OfflineAsset(
            id = "asset-missing",
            entryId = entry.id,
            kind = AssetKind.ARTWORK,
            relativeLocator = "covers/aula.jpg",
            byteCount = 0L,
            sha256 = "",
            state = AssetState.MISSING,
            isRequired = true,
            mediaRevision = mediaRev,
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(
            entry = entry,
            assets = listOf(requiredArtwork),
            fileInspector = inspector,
        )

        assertEquals(ReadinessStatus.INCOMPLETE, readiness.status)
        assertFalse(readiness.isReady)
        assertFalse(readiness.isGreen)
        assertEquals(1, readiness.missingRequirements.size)
        val missing = readiness.missingRequirements.single()
        assertEquals(AssetKind.ARTWORK, missing.kind)
        assertEquals(RecoveryAction.FETCH_ARTWORK, missing.suggestedAction)
    }
}
