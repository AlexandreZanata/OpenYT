package com.openyt.core.domain

import com.openyt.core.application.FileMetadata
import com.openyt.core.application.MediaFileInspector
import com.openyt.core.application.OfflineReadinessCalculator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineReadinessTest {

    private class FakeMediaFileInspector : MediaFileInspector {
        val files = mutableMapOf<String, FakeFile>()

        data class FakeFile(
            var sizeBytes: Long,
            var lastModifiedEpochMillis: Long,
            var sha256: String,
            var readable: Boolean = true,
        )

        fun addFile(path: String, sizeBytes: Long, lastModified: Long, sha256: String) {
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

    @Test
    fun missingMediaMarksReadinessUnavailableWithRelinkAction() {
        val inspector = FakeMediaFileInspector()
        // Do not add file to inspector
        val entry = LibraryEntry(
            id = "entry-1",
            title = "Missing Video",
            kind = MediaKind.VIDEO,
            filePath = "/media/missing.mp4",
            mediaRevision = MediaRevision(sizeBytes = 1000L, lastModifiedEpochMillis = 5000L, contentSha256 = "deadbeef"),
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(entry, emptyList(), inspector)

        assertEquals(ReadinessStatus.UNAVAILABLE, readiness.status)
        assertFalse(readiness.isReady)
        assertFalse(readiness.isGreen)
        assertFalse(readiness.isMediaAccessible)
        assertEquals(1, readiness.missingRequirements.size)
        val missing = readiness.missingRequirements.single()
        assertEquals(AssetKind.PRIMARY_MEDIA, missing.kind)
        assertEquals(RecoveryAction.RELINK_MEDIA, missing.suggestedAction)
    }

    @Test
    fun changedBytesAtSamePathInvalidatesRevisionAndMarksReadinessStale() {
        val inspector = FakeMediaFileInspector()
        val path = "/media/video.mp4"
        // Recorded revision was size 1000, mtime 5000
        val recordedRevision = MediaRevision(sizeBytes = 1000L, lastModifiedEpochMillis = 5000L, contentSha256 = "original-hash")

        // File on disk was modified (size 1200, mtime 6000)
        inspector.addFile(path, sizeBytes = 1200L, lastModified = 6000L, sha256 = "new-hash")

        val entry = LibraryEntry(
            id = "entry-1",
            title = "Video Title",
            kind = MediaKind.VIDEO,
            filePath = path,
            mediaRevision = recordedRevision,
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(entry, emptyList(), inspector)

        assertEquals(ReadinessStatus.STALE, readiness.status)
        assertFalse(readiness.isReady)
        assertFalse(readiness.isGreen)
        assertTrue(readiness.isMediaAccessible) // File is present, but stale
        assertEquals(1, readiness.missingRequirements.size)
        val missing = readiness.missingRequirements.single()
        assertEquals(AssetKind.PRIMARY_MEDIA, missing.kind)
        assertEquals(RecoveryAction.REVERIFY, missing.suggestedAction)
    }

    @Test
    fun subtitleOnlyEntrySucceedsWithoutVideoOrAudioTracks() {
        val inspector = FakeMediaFileInspector()
        val path = "/media/subtitles.srt"
        inspector.addFile(path, sizeBytes = 450L, lastModified = 1000L, sha256 = "sub-sha256")

        val entry = LibraryEntry(
            id = "sub-entry-1",
            title = "Portuguese Subtitles",
            kind = MediaKind.SUBTITLE,
            filePath = path,
            mediaRevision = MediaRevision(sizeBytes = 450L, lastModifiedEpochMillis = 1000L, contentSha256 = "sub-sha256"),
            lastVerifiedEpochMillis = 2000L,
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(entry, emptyList(), inspector)

        assertEquals(ReadinessStatus.READY, readiness.status)
        assertTrue(readiness.isReady)
        assertTrue(readiness.isGreen)
        assertTrue(readiness.isMediaAccessible)
        assertTrue(readiness.missingRequirements.isEmpty())
    }

    @Test
    fun optionalArtworkMissingDoesNotBlockReadiness() {
        val inspector = FakeMediaFileInspector()
        val path = "/media/imported_audio.m4a"
        inspector.addFile(path, sizeBytes = 8000L, lastModified = 2000L, sha256 = "audio-sha256")

        // Imported media without requested artwork (no required artwork asset recorded)
        val entry = LibraryEntry(
            id = "import-1",
            title = "Podcast Episode",
            kind = MediaKind.AUDIO,
            filePath = path,
            thumbnailUrl = null, // No artwork requested
            mediaRevision = MediaRevision(sizeBytes = 8000L, lastModifiedEpochMillis = 2000L, contentSha256 = "audio-sha256"),
        )

        // An optional artwork asset that happens to be MISSING
        val optionalArtwork = OfflineAsset(
            id = "art-1",
            entryId = entry.id,
            kind = AssetKind.ARTWORK,
            relativeLocator = "covers/art-1.jpg",
            byteCount = 0L,
            sha256 = "",
            state = AssetState.MISSING,
            isRequired = false, // Optional!
            mediaRevision = entry.mediaRevision!!,
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(entry, listOf(optionalArtwork), inspector)

        // Does NOT fail a nonexistent or optional requirement
        assertEquals(ReadinessStatus.READY, readiness.status)
        assertTrue(readiness.isReady)
        assertTrue(readiness.isGreen)
        assertTrue(readiness.missingRequirements.isEmpty())
    }

    @Test
    fun requiredArtworkMissingBlocksReadinessAndSuggestsFetch() {
        val inspector = FakeMediaFileInspector()
        val path = "/media/video.mp4"
        inspector.addFile(path, sizeBytes = 50000L, lastModified = 3000L, sha256 = "video-sha256")

        val mediaRev = MediaRevision(sizeBytes = 50000L, lastModifiedEpochMillis = 3000L, contentSha256 = "video-sha256")
        val entry = LibraryEntry(
            id = "entry-art",
            title = "Study Video",
            kind = MediaKind.VIDEO,
            filePath = path,
            thumbnailUrl = "https://example.com/thumb.jpg",
            mediaRevision = mediaRev,
        )

        // Required artwork is MISSING
        val requiredArtwork = OfflineAsset(
            id = "art-required",
            entryId = entry.id,
            kind = AssetKind.ARTWORK,
            relativeLocator = "covers/art-required.jpg",
            byteCount = 0L,
            sha256 = "",
            state = AssetState.MISSING,
            isRequired = true,
            mediaRevision = mediaRev,
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(entry, listOf(requiredArtwork), inspector)

        assertEquals(ReadinessStatus.INCOMPLETE, readiness.status)
        assertFalse(readiness.isReady)
        assertFalse(readiness.isGreen)
        assertEquals(1, readiness.missingRequirements.size)
        val missing = readiness.missingRequirements.single()
        assertEquals(AssetKind.ARTWORK, missing.kind)
        assertEquals(RecoveryAction.FETCH_ARTWORK, missing.suggestedAction)
    }

    @Test
    fun requiredArtworkVerifiedYieldsReadyStatus() {
        val inspector = FakeMediaFileInspector()
        val path = "/media/video.mp4"
        inspector.addFile(path, sizeBytes = 50000L, lastModified = 3000L, sha256 = "video-sha256")

        val mediaRev = MediaRevision(sizeBytes = 50000L, lastModifiedEpochMillis = 3000L, contentSha256 = "video-sha256")
        val entry = LibraryEntry(
            id = "entry-art",
            title = "Study Video",
            kind = MediaKind.VIDEO,
            filePath = path,
            mediaRevision = mediaRev,
        )

        val verifiedArtwork = OfflineAsset(
            id = "art-verified",
            entryId = entry.id,
            kind = AssetKind.ARTWORK,
            relativeLocator = "covers/art-verified.jpg",
            byteCount = 1234L,
            sha256 = "art-sha256",
            state = AssetState.VERIFIED,
            isRequired = true,
            mediaRevision = mediaRev,
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(entry, listOf(verifiedArtwork), inspector)

        assertEquals(ReadinessStatus.READY, readiness.status)
        assertTrue(readiness.isReady)
        assertTrue(readiness.isGreen)
        assertEquals(1, readiness.verifiedAssets.size)
        assertEquals("art-verified", readiness.verifiedAssets.single().id)
        assertTrue(readiness.missingRequirements.isEmpty())
    }

    @Test
    fun oldRecordsWithUnknownAssetStateRemainPendingUntilVerified() {
        val inspector = FakeMediaFileInspector()
        val path = "/media/legacy.mp4"
        inspector.addFile(path, sizeBytes = 30000L, lastModified = 1000L, sha256 = "legacy-hash")

        // Old record migrated from schema 19 or earlier with no mediaRevision recorded
        val entry = LibraryEntry(
            id = "legacy-1",
            title = "Migrated Item",
            kind = MediaKind.VIDEO,
            filePath = path,
            mediaRevision = null, // Unknown asset state!
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(entry, emptyList(), inspector)

        assertEquals(ReadinessStatus.PENDING, readiness.status)
        assertFalse(readiness.isReady)
        assertFalse(readiness.isGreen)
        assertTrue(readiness.isMediaAccessible)
        assertEquals(1, readiness.missingRequirements.size)
        val missing = readiness.missingRequirements.single()
        assertEquals(AssetKind.PRIMARY_MEDIA, missing.kind)
        assertEquals(RecoveryAction.REVERIFY, missing.suggestedAction)
    }

    @Test
    fun remoteThumbnailUrlPresenceNeverFakesOfflineReadiness() {
        val inspector = FakeMediaFileInspector()
        val path = "/media/video.mp4"
        inspector.addFile(path, sizeBytes = 10000L, lastModified = 2000L, sha256 = "v-sha")

        val mediaRev = MediaRevision(sizeBytes = 10000L, lastModifiedEpochMillis = 2000L, contentSha256 = "v-sha")
        val entry = LibraryEntry(
            id = "entry-remote",
            title = "Online Video",
            kind = MediaKind.VIDEO,
            filePath = path,
            thumbnailUrl = "https://i.ytimg.com/vi/abc/hqdefault.jpg", // Remote URL present!
            mediaRevision = mediaRev,
        )

        // Required artwork has not been downloaded or verified offline
        val pendingArtwork = OfflineAsset(
            id = "art-pending",
            entryId = entry.id,
            kind = AssetKind.ARTWORK,
            relativeLocator = "covers/art-pending.jpg",
            byteCount = 0L,
            sha256 = "",
            state = AssetState.PENDING,
            isRequired = true,
            mediaRevision = mediaRev,
        )

        val readiness = OfflineReadinessCalculator.computeReadiness(entry, listOf(pendingArtwork), inspector)

        // URL presence must NEVER make it green!
        assertFalse(readiness.isReady)
        assertFalse(readiness.isGreen)
        assertEquals(ReadinessStatus.PENDING, readiness.status)
    }

    @Test
    fun neverUseDisplayNameAsContentIdentifier() {
        val inspector = FakeMediaFileInspector()
        val pathA = "/media/fileA.mp4"
        val pathB = "/media/fileB.mp4"
        inspector.addFile(pathA, sizeBytes = 1000L, lastModified = 1000L, sha256 = "hash-A")
        inspector.addFile(pathB, sizeBytes = 2000L, lastModified = 2000L, sha256 = "hash-B")

        val sharedDisplayName = "Same Course Lesson"

        val entryA = LibraryEntry(
            id = "id-A",
            title = sharedDisplayName,
            kind = MediaKind.VIDEO,
            filePath = pathA,
            mediaRevision = MediaRevision(1000L, 1000L, "hash-A"),
        )
        val entryB = LibraryEntry(
            id = "id-B",
            title = sharedDisplayName,
            kind = MediaKind.VIDEO,
            filePath = pathB,
            mediaRevision = MediaRevision(2000L, 2000L, "hash-B"),
        )

        val readinessA = OfflineReadinessCalculator.computeReadiness(entryA, emptyList(), inspector)
        val readinessB = OfflineReadinessCalculator.computeReadiness(entryB, emptyList(), inspector)

        assertTrue(readinessA.isReady)
        assertTrue(readinessB.isReady)
        assertEquals("id-A", readinessA.entryId)
        assertEquals("id-B", readinessB.entryId)

        // Renaming display name of entry A does not affect revision or readiness
        val renamedA = entryA.copy(title = "Completely New Title")
        val readinessRenamed = OfflineReadinessCalculator.computeReadiness(renamedA, emptyList(), inspector)
        assertTrue(readinessRenamed.isReady)
        assertEquals(readinessA.mediaRevision, readinessRenamed.mediaRevision)
    }

    @Test
    fun verifyEntryMediaPerformsDigestComputationOffUiThread() = runBlocking {
        val inspector = FakeMediaFileInspector()
        val path = "/media/doc.mp4"
        inspector.addFile(path, sizeBytes = 9999L, lastModified = 4444L, sha256 = "computed-sha256")

        val entry = LibraryEntry(
            id = "doc-1",
            title = "Documentary",
            kind = MediaKind.VIDEO,
            filePath = path,
        )

        val result = OfflineReadinessCalculator.verifyEntryMedia(entry, inspector, verifyDigest = true)
        assertTrue(result.isSuccess)
        val revision = result.getOrThrow()
        assertEquals(9999L, revision.sizeBytes)
        assertEquals(4444L, revision.lastModifiedEpochMillis)
        assertEquals("computed-sha256", revision.contentSha256)
        assertEquals("9999-4444-computed-sha256", revision.revisionToken)
    }

    @Test
    fun structuredLibraryOperationFailuresProvideActionableRecovery() {
        val missing = LibraryOperationFailure.MediaMissing("/tmp/none.mp4")
        assertEquals(RecoveryAction.RELINK_MEDIA, missing.suggestedAction)

        val changed = LibraryOperationFailure.MediaRevisionChanged(
            path = "/tmp/a.mp4",
            expectedRevision = MediaRevision(100L, 200L),
            actualSizeBytes = 150L,
            actualMtimeMillis = 250L,
        )
        assertEquals(RecoveryAction.REVERIFY, changed.suggestedAction)

        val checksum = LibraryOperationFailure.AssetChecksumMismatch("asset-1", "exp", "act")
        assertEquals(RecoveryAction.RETRY_DOWNLOAD, checksum.suggestedAction)

        val budget = LibraryOperationFailure.DiskBudgetExceeded(1000L, 500L, 800L)
        assertEquals(RecoveryAction.CLEANUP_STALE, budget.suggestedAction)

        val reserve = LibraryOperationFailure.StorageReserveBreached(1000L, 400L)
        assertEquals(RecoveryAction.CLEANUP_STALE, reserve.suggestedAction)

        val perm = LibraryOperationFailure.PermissionDenied("/root/secret.mp4")
        assertEquals(RecoveryAction.RELINK_MEDIA, perm.suggestedAction)
    }
}
