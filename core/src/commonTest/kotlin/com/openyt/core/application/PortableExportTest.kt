package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableExportTest {

    @Test
    fun failsCleanlyOnInsufficientSpaceMissingMediaAndPermissionDeniedBeforeCreatingArchive() = runBlocking {
        val entries = listOf(
            LibraryEntry("e1", "Missing Clip", MediaKind.VIDEO, "/missing.mp4", 1024L, 100L),
            LibraryEntry("e2", "Locked Clip", MediaKind.AUDIO, "/locked.m4a", 2048L, 200L),
            LibraryEntry("e3", "Valid Clip", MediaKind.VIDEO, "/valid.mp4", 500L, 300L),
        )
        val storage = FakeExportStorageAccess(
            sources = mutableMapOf(
                "/locked.m4a" to PortableSourceMetadata.PermissionDenied("locked.m4a"),
                "/valid.mp4" to PortableSourceMetadata.Readable(500L, 1000L, "valid.mp4"),
            ),
            availableDestBytes = 500_000_000L,
        )
        val writer = FakeArchiveWriterPort()
        val journal = MemoryPortableOperationRepository()
        val service = PortableBackupExportService(
            snapshotSource = {
                PortableSnapshotData(
                    entries = entries,
                    collections = emptyList(),
                    collectionMembership = emptyMap(),
                    smartCollections = emptyList(),
                    playbackByEntryId = emptyMap(),
                    studyBundlesByEntryId = emptyMap(),
                    offlineAssetsByEntryId = emptyMap(),
                    customProfiles = emptyList(),
                )
            },
            writerPort = writer,
            storageAccess = storage,
            journalRepository = journal,
            storageReserveBytes = 10_000_000L,
        )

        // Default strict export rejects missing/denied items without opening writer
        val strictFailure = service.exportBackup("/out/backup.openyt-backup.zip").exceptionOrNull() as PortableBackupException
        assertTrue(strictFailure.failure is PortableBackupFailure.SourceMissing)
        assertEquals(0, writer.openCalls)

        // With allowPartialMissing = true, explicitly reports skipped items while still succeeding
        val partialReceipt = service.exportBackup(
            "/out/backup.openyt-backup.zip",
            PortableExportOptions(allowPartialMissing = true),
        ).getOrThrow()
        assertEquals(3, partialReceipt.entryCount)
        assertEquals(1, partialReceipt.mediaFileCount)
        assertEquals(2, partialReceipt.skippedItems.size)

        // Insufficient space rejects BEFORE opening writer
        storage.availableDestBytes = 500_000L // less than reserve 10_000_000L
        val spaceErr = service.exportBackup(
            "/out/backup2.openyt-backup.zip",
            PortableExportOptions(allowPartialMissing = true),
        ).exceptionOrNull() as PortableBackupException
        assertTrue(spaceErr.failure is PortableBackupFailure.InsufficientSpace)
        assertEquals(1, writer.openCalls)
    }

    @Test
    fun detectsMidCopySourceChangeAbortsSessionAndLeavesNoIncompleteOutput() = runBlocking {
        val storage = FakeExportStorageAccess(
            sources = mutableMapOf(
                "/changing.mp4" to PortableSourceMetadata.Readable(1024L, 1000L, "changing.mp4"),
            ),
            availableDestBytes = 500_000_000L,
        )
        val writer = FakeArchiveWriterPort(
            onWriteFile = { _ ->
                // Mutate file mtime while copy is running
                storage.sources["/changing.mp4"] = PortableSourceMetadata.Readable(1024L, 2000L, "changing.mp4")
            },
        )
        val journal = MemoryPortableOperationRepository()
        val service = PortableBackupExportService(
            snapshotSource = {
                PortableSnapshotData(
                    entries = listOf(LibraryEntry("e1", "Changing", MediaKind.VIDEO, "/changing.mp4", 1024L, 1L)),
                    collections = emptyList(),
                    collectionMembership = emptyMap(),
                    smartCollections = emptyList(),
                    playbackByEntryId = emptyMap(),
                    studyBundlesByEntryId = emptyMap(),
                    offlineAssetsByEntryId = emptyMap(),
                    customProfiles = emptyList(),
                )
            },
            writerPort = writer,
            storageAccess = storage,
            journalRepository = journal,
        )

        val err = service.exportBackup("/out/changing.openyt-backup.zip").exceptionOrNull() as PortableBackupException
        assertTrue(err.failure is PortableBackupFailure.SourceChanged)
        assertTrue(writer.lastSession?.aborted == true)
        assertFalse(writer.lastSession?.committed == true)
        assertEquals(PortableOperationStatus.FAILED, journal.listOperations().single().status)
    }

    @Test
    fun cancellationCleansStagingAndMarksInterruptedOnStartup() = runBlocking {
        val storage = FakeExportStorageAccess(
            sources = mutableMapOf(
                "/big.mp4" to PortableSourceMetadata.Readable(4096L, 1000L, "big.mp4"),
            ),
            availableDestBytes = 500_000_000L,
        )
        val writer = FakeArchiveWriterPort(
            onWriteFile = {
                delay(200)
            },
        )
        val journal = MemoryPortableOperationRepository()
        val service = PortableBackupExportService(
            snapshotSource = {
                PortableSnapshotData(
                    entries = listOf(LibraryEntry("e1", "Big", MediaKind.VIDEO, "/big.mp4", 4096L, 1L)),
                    collections = emptyList(),
                    collectionMembership = emptyMap(),
                    smartCollections = emptyList(),
                    playbackByEntryId = emptyMap(),
                    studyBundlesByEntryId = emptyMap(),
                    offlineAssetsByEntryId = emptyMap(),
                    customProfiles = emptyList(),
                )
            },
            writerPort = writer,
            storageAccess = storage,
            journalRepository = journal,
        )

        val job: Job = launch {
            try {
                service.exportBackup("/out/canceled.openyt-backup.zip")
            } catch (_: CancellationException) {
            }
        }
        delay(20)
        job.cancelAndJoin()
        assertTrue(writer.lastSession?.aborted == true)
        assertEquals(PortableOperationStatus.CANCELED, journal.listOperations().single().status)

        // Simulate prior crashed RUNNING job reconciled at startup
        journal.upsertOperation(
            PortableOperationJournalRecord(
                id = "crashed-op",
                direction = PortableOperationDirection.EXPORT,
                status = PortableOperationStatus.RUNNING,
                archiveLocator = "/out/crashed.zip",
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 10L,
            ),
        )
        storage.activeStagingIds += "crashed-op"
        service.reconcileInterruptedOperations()
        assertEquals(PortableOperationStatus.INTERRUPTED, journal.getOperation("crashed-op")?.status)
        assertTrue("crashed-op" !in storage.activeStagingIds)
    }

    private class FakeExportStorageAccess(
        val sources: MutableMap<String, PortableSourceMetadata>,
        var availableDestBytes: Long,
    ) : PortableStorageAccessPort {
        val activeStagingIds = mutableSetOf<String>()
        override fun availableBytesForDestination(locator: String): Long = availableDestBytes
        override fun availableBytesForStaging(): Long = availableDestBytes
        override fun inspectSource(sourceLocator: String): PortableSourceMetadata =
            sources[sourceLocator] ?: PortableSourceMetadata.Missing
        override suspend fun prepareStagingDirectory(operationId: String): String {
            activeStagingIds += operationId
            return "/staging/$operationId"
        }
        override suspend fun cleanupStagingDirectory(operationId: String) {
            activeStagingIds -= operationId
        }
        override suspend fun cleanupOrphanedStaging(activeOperationIds: Set<String>) {
            activeStagingIds.retainAll(activeOperationIds)
        }
        override suspend fun copyStagedFileToOwnedTarget(
            stagedPath: String,
            destinationRoot: String,
            relativeSubdir: String,
            desiredFileName: String,
            expectedSha256: String,
            expectedByteCount: Long,
        ): Result<PortableOwnedFileReceipt> = Result.failure(UnsupportedOperationException())
        override suspend fun checkFileSha256(locator: String): String? = null
    }

    private class FakeArchiveWriterPort(
        private val onWriteFile: suspend (String) -> Unit = {},
    ) : PortableArchiveWriterPort {
        var openCalls = 0
        var lastSession: FakeSession? = null

        override suspend fun openOutputSession(
            archiveLocator: String,
            operationId: String,
        ): Result<PortableArchiveOutputSession> {
            openCalls++
            val session = FakeSession(archiveLocator, onWriteFile)
            lastSession = session
            return Result.success(session)
        }

        class FakeSession(
            override val archiveLocator: String,
            private val onWriteFile: suspend (String) -> Unit,
        ) : PortableArchiveOutputSession {
            var committed = false
            var aborted = false
            val writtenJson = mutableMapOf<String, String>()

            override suspend fun writeFileEntry(
                archivePath: String,
                sourceLocator: String,
                onProgress: suspend (Long) -> Unit,
            ): Result<PortableStreamedBlobInfo> {
                onWriteFile(sourceLocator)
                val size = if (sourceLocator == "/changing.mp4") 1024L else 500L
                onProgress(size)
                return Result.success(PortableStreamedBlobInfo(archivePath, size, "e".repeat(64)))
            }

            override suspend fun writeJsonEntry(archivePath: String, content: String): Result<PortableStreamedBlobInfo> {
                writtenJson[archivePath] = content
                return Result.success(PortableStreamedBlobInfo(archivePath, content.length.toLong(), "f".repeat(64)))
            }

            override suspend fun commit(): Result<String> {
                committed = true
                return Result.success("a".repeat(64))
            }

            override suspend fun abortAndCleanup() {
                aborted = true
            }
        }
    }
}
