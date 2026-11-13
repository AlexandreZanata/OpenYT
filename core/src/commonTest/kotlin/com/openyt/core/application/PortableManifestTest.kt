package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.SmartCollection
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableManifestTest {

    private val sampleShaA = "a".repeat(64)
    private val sampleShaB = "b".repeat(64)

    @Test
    fun goldenManifestRoundTripsDeterministicallyWithSecretAndPathExclusions() {
        val snapshot = PortableSnapshotData(
            entries = listOf(
                LibraryEntry(
                    id = "entry-lecture-1",
                    title = "Lesson 01 — Phonetics & Intonation",
                    kind = MediaKind.VIDEO,
                    filePath = "/home/user/SecretFolder/lesson-01.mp4",
                    sizeBytes = 1024L,
                    createdAtEpochMillis = 1729990000000L,
                    thumbnailUrl = "https://alice:topsecret@cdn.example.org/thumb/lesson1.jpg?token=leak123&sig=abc",
                    source = "youtube",
                    tags = listOf("linguistics", "en-us", "linguistics"),
                    author = "Prof. Ada",
                    mediaRevision = MediaRevision(1024L, 1729990001000L, sampleShaA),
                    lastVerifiedEpochMillis = 1729990001000L,
                ),
            ),
            collections = listOf(Collection("col-course-1", "Phonetics Course")),
            collectionMembership = mapOf("col-course-1" to setOf("entry-lecture-1")),
            smartCollections = listOf(
                SmartCollection(
                    id = "smart-en-us",
                    name = "English Drills",
                    query = "Phonetics",
                    kind = MediaKind.VIDEO,
                    tag = "en-us",
                ),
            ),
            playbackByEntryId = mapOf(
                "entry-lecture-1" to PlaybackCheckpoint(45200L, 180000L, "entry-lecture-1"),
            ),
            studyBundlesByEntryId = mapOf(
                "entry-lecture-1" to EntryStudyBundle(
                    entryId = "entry-lecture-1",
                    entryExists = true,
                    currentMediaRevision = MediaRevision(1024L, 1729990001000L, sampleShaA),
                    notes = listOf(
                        StudyNote(
                            id = "note-1",
                            entryId = "entry-lecture-1",
                            timestampMillis = 12000L,
                            endMillis = 18500L,
                            body = "Review stress pattern on compound nouns.",
                            mediaRevision = MediaRevision(1024L, 1729990001000L, sampleShaA),
                            createdAtEpochMillis = 1729990010000L,
                            updatedAtEpochMillis = 1729990020000L,
                        ),
                    ),
                    bookmarks = listOf(
                        StudyBookmark(
                            id = "bm-1",
                            entryId = "entry-lecture-1",
                            timestampMillis = 45000L,
                            label = "Minimal pairs demo",
                            mediaRevision = MediaRevision(1024L, 1729990001000L, sampleShaA),
                            createdAtEpochMillis = 1729990030000L,
                            updatedAtEpochMillis = 1729990030000L,
                        ),
                    ),
                    savedRanges = listOf(
                        SavedStudyRange(
                            id = "range-1",
                            entryId = "entry-lecture-1",
                            startMillis = 60000L,
                            endMillis = 75000L,
                            label = "Drill loop",
                            mediaRevision = MediaRevision(1024L, 1729990001000L, sampleShaA),
                            createdAtEpochMillis = 1729990040000L,
                            updatedAtEpochMillis = 1729990040000L,
                        ),
                    ),
                    studyState = EntryStudyState("entry-lecture-1", true, 1729990050000L, 1729990050000L),
                ),
            ),
            offlineAssetsByEntryId = mapOf(
                "entry-lecture-1" to listOf(
                    OfflineAsset(
                        id = "asset-sub-pt",
                        entryId = "entry-lecture-1",
                        kind = AssetKind.SUBTITLE,
                        language = "pt",
                        sourceType = "manual",
                        relativeLocator = "entries/entry-lecture-1/sub.pt.srt",
                        byteCount = 256L,
                        sha256 = sampleShaB,
                        state = AssetState.VERIFIED,
                        isRequired = false,
                        isPinned = true,
                        mediaRevision = MediaRevision(1024L, 1729990001000L, sampleShaA),
                        createdAtEpochMillis = 1729990005000L,
                    ),
                ),
            ),
            customProfiles = listOf(
                DownloadProfile.StudyOfflineStub(),
                DownloadProfile(
                    id = "profile-custom-1",
                    name = "Podcast Compact",
                    isBuiltIn = false,
                    kind = MediaKind.AUDIO,
                    preferMp4Avc = false,
                    audioPreset = com.openyt.core.domain.AudioPreset.MP3,
                ),
            ),
        )

        val manifest = PortableSnapshotCollector.assembleManifest(
            snapshot = snapshot,
            bundleId = "golden-bundle-v1",
            createdAtEpochMillis = 1730000000000L,
            appVersion = "4.1.0",
            primaryBlobsByEntryId = mapOf(
                "entry-lecture-1" to PortableBlobRef(
                    archivePath = "media/entry-lecture-1/lesson-01.mp4",
                    byteCount = 1024L,
                    sha256 = sampleShaA,
                    originalFileName = "lesson-01.mp4",
                    mediaKind = MediaKind.VIDEO,
                    lastModifiedEpochMillis = 1729990001000L,
                ),
            ),
            assetBlobsByAssetId = mapOf(
                "asset-sub-pt" to PortableBlobRef(
                    archivePath = "assets/entry-lecture-1/lesson-01.pt.srt",
                    byteCount = 256L,
                    sha256 = sampleShaB,
                    originalFileName = "lesson-01.pt.srt",
                    mediaKind = MediaKind.SUBTITLE,
                ),
            ),
        )

        val encoded = PortableBackupCodec.encode(manifest, pretty = true)
        assertFalse(encoded.contains("/home/user/SecretFolder"))
        assertFalse(encoded.contains("topsecret"))
        assertFalse(encoded.contains("leak123"))
        assertTrue(encoded.contains("https://cdn.example.org/thumb/lesson1.jpg"))
        assertTrue(encoded.contains(PortableBackupFormat.PLAINTEXT_PRIVACY_MARKER))
        // Built-in profiles are excluded; only custom profile remains
        assertEquals(listOf("profile-custom-1"), manifest.downloadProfiles.map { it.exportId })

        val decoded = PortableBackupCodec.decode(encoded).getOrThrow()
        assertEquals(manifest, decoded)
    }

    @Test
    fun rejectsUnsupportedFutureMinReaderVersionAndTraversalOrSecretUrls() {
        val futureJson = """
            {
              "formatVersion": 2,
              "minReaderVersion": 2,
              "bundleId": "future",
              "createdAtEpochMillis": 1,
              "privacyNotice": "UNENCRYPTED_PORTABLE_BACKUP"
            }
        """.trimIndent()
        val futureErr = PortableBackupCodec.decode(futureJson).exceptionOrNull() as PortableBackupException
        assertTrue(futureErr.failure is PortableBackupFailure.UnsupportedVersion)

        val unknownCompatibleJson = """
            {
              "formatVersion": 2,
              "minReaderVersion": 1,
              "bundleId": "compat",
              "createdAtEpochMillis": 1,
              "privacyNotice": "UNENCRYPTED_PORTABLE_BACKUP",
              "futureExperimentalField": "ignored"
            }
        """.trimIndent()
        assertTrue(PortableBackupCodec.decode(unknownCompatibleJson).isSuccess)

        val malicious = PortableBackupManifest(
            bundleId = "bad",
            createdAtEpochMillis = 1L,
            entries = listOf(
                PortableEntryRecord(
                    exportId = "e1",
                    title = "Track",
                    kind = MediaKind.AUDIO,
                    primaryMedia = PortableBlobRef(
                        archivePath = "media/../etc/passwd",
                        byteCount = 10L,
                        sha256 = sampleShaA,
                        originalFileName = "a.mp3",
                    ),
                ),
            ),
        )
        val pathErr = PortableBackupCodec.validateManifest(malicious).exceptionOrNull() as PortableBackupException
        assertTrue(pathErr.failure is PortableBackupFailure.SecurityViolation)
    }

    @Test
    fun rejectsCaseInsensitiveBlobCollisionAndDanglingCollectionReference() {
        val caseCollision = PortableBackupManifest(
            bundleId = "dup",
            createdAtEpochMillis = 1L,
            entries = listOf(
                PortableEntryRecord(
                    exportId = "e1",
                    title = "One",
                    kind = MediaKind.AUDIO,
                    primaryMedia = PortableBlobRef("media/a/Song.mp3", 10L, sampleShaA, "Song.mp3"),
                ),
                PortableEntryRecord(
                    exportId = "e2",
                    title = "Two",
                    kind = MediaKind.AUDIO,
                    primaryMedia = PortableBlobRef("media/a/song.mp3", 10L, sampleShaB, "song.mp3"),
                ),
            ),
        )
        assertTrue(PortableBackupCodec.validateManifest(caseCollision).isFailure)

        val dangling = PortableBackupManifest(
            bundleId = "dangling",
            createdAtEpochMillis = 1L,
            collections = listOf(PortableCollectionRecord("c1", "Col", listOf("missing-entry"))),
        )
        assertTrue(PortableBackupCodec.validateManifest(dangling).isFailure)
    }

    @Test
    fun idRemapperPreservesRelationsAcrossConflictingAndSkippedEntries() {
        val manifest = PortableBackupManifest(
            bundleId = "remap",
            createdAtEpochMillis = 100L,
            entries = listOf(
                PortableEntryRecord(
                    exportId = "e1",
                    title = "Imported Lecture",
                    kind = MediaKind.VIDEO,
                    primaryMedia = PortableBlobRef("media/e1/video.mp4", 500L, sampleShaA, "video.mp4"),
                    playbackCheckpoint = PortablePlaybackCheckpoint(25000L, 90000L),
                    study = PortableEntryStudySnapshot(
                        notes = listOf(PortableStudyNote("n1", 1000L, null, "Note", null, 10L, 10L)),
                        bookmarks = listOf(PortableStudyBookmark("b1", 2000L, "BM", null, 10L, 10L)),
                        ranges = listOf(PortableStudyRange("r1", 3000L, 5000L, "Loop", null, 10L, 10L)),
                        isStudied = true,
                        studiedAtEpochMillis = 50L,
                    ),
                    offlineAssets = listOf(
                        PortableOfflineAssetRecord(
                            exportId = "a1",
                            kind = AssetKind.SUBTITLE,
                            language = "en",
                            mediaRevision = MediaRevision(500L, 10L, sampleShaA),
                            blob = PortableBlobRef("assets/e1/sub.srt", 100L, sampleShaB, "sub.srt"),
                        ),
                    ),
                ),
            ),
            collections = listOf(PortableCollectionRecord("c1", "Existing Col", listOf("e1"))),
            smartCollections = listOf(PortableSmartCollectionRecord("s1", "Existing Smart", query = "foo")),
            downloadProfiles = listOf(
                PortableDownloadProfileRecord(
                    exportId = "p1",
                    profile = DownloadProfile(id = "p1", name = "My Profile", isBuiltIn = false),
                ),
            ),
        )

        val existing = PortableSnapshotData(
            entries = listOf(
                LibraryEntry(
                    id = "e1",
                    title = "Preexisting Same Id & SHA",
                    kind = MediaKind.VIDEO,
                    filePath = "/existing/video.mp4",
                    mediaRevision = MediaRevision(500L, 10L, sampleShaA),
                ),
            ),
            collections = listOf(Collection("c1", "Existing Col")),
            collectionMembership = emptyMap(),
            smartCollections = listOf(SmartCollection("s1", "Existing Smart")),
            playbackByEntryId = emptyMap(),
            studyBundlesByEntryId = mapOf(
                "e1" to EntryStudyBundle(
                    entryId = "e1",
                    entryExists = true,
                    currentMediaRevision = null,
                    notes = listOf(StudyNote("n1", "e1", 0L, null, "Existing note", null, 1L, 1L)),
                    bookmarks = listOf(StudyBookmark("b1", "e1", 0L, "Existing bm", null, 1L, 1L)),
                    savedRanges = listOf(SavedStudyRange("r1", "e1", 0L, 100L, null, null, 1L, 1L)),
                    studyState = EntryStudyState("e1", false),
                ),
            ),
            offlineAssetsByEntryId = mapOf(
                "e1" to listOf(
                    OfflineAsset("a1", "e1", AssetKind.ARTWORK, null, null, "t", 10L, sampleShaB, AssetState.VERIFIED, false, false, MediaRevision(1, 1), 1L),
                ),
            ),
            customProfiles = listOf(DownloadProfile(id = "p1", name = "My Profile", isBuiltIn = false)),
        )

        // IMPORT_AS_NEW remaps everything cleanly with zero collisions
        val importNewPlan = PortableIdRemapper.buildImportPlan(
            manifest = manifest,
            existing = existing,
            conflictPolicy = PortableRestoreConflictPolicy.IMPORT_AS_NEW,
            nowEpochMillis = 999L,
        )
        assertEquals(1, importNewPlan.entriesToImport.size)
        val remappedEntry = importNewPlan.entriesToImport.single()
        assertFalse(remappedEntry.targetEntryId == "e1")
        assertEquals(remappedEntry.targetEntryId, remappedEntry.notes.single().entryId)
        assertFalse(remappedEntry.notes.single().id == "n1")
        assertEquals(remappedEntry.targetEntryId, remappedEntry.assets.single().asset.entryId)
        assertFalse(remappedEntry.assets.single().asset.id == "a1")
        assertEquals(listOf(importNewPlan.collections.single().id to remappedEntry.targetEntryId), importNewPlan.collectionMembership)
        assertTrue(importNewPlan.collections.single().name.endsWith("(backup)"))
        assertTrue(importNewPlan.smartCollections.single().name.endsWith("(backup)"))
        assertTrue(importNewPlan.customProfiles.single().name.endsWith("(backup)"))

        // SKIP_IF_PRESENT skips duplicate entry and existing profile
        val skipPlan = PortableIdRemapper.buildImportPlan(
            manifest = manifest,
            existing = existing,
            conflictPolicy = PortableRestoreConflictPolicy.SKIP_IF_PRESENT,
            nowEpochMillis = 999L,
        )
        assertTrue(skipPlan.entriesToImport.isEmpty())
        assertEquals(listOf("e1"), skipPlan.skippedDuplicateEntryIds)
        assertEquals(listOf("p1"), skipPlan.skippedProfileIds)
        assertNull(PortableArchivePathRules.sanitizeExternalUrlOrNull("file:///etc/passwd"))
        assertNull(PortableArchivePathRules.sanitizeExternalUrlOrNull("content://media/external/video/1"))
    }
}

private fun DownloadProfile.Companion.StudyOfflineStub(): DownloadProfile =
    DownloadProfile(id = BUILTIN_STUDY, name = "BuiltInStudy", isBuiltIn = true)
