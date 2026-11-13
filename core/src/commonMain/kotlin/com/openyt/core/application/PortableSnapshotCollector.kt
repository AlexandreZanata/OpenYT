package com.openyt.core.application

import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.SmartCollection

/**
 * Consistent read snapshot across SQLDelight library tables captured inside a single transaction
 * for deterministic portable backup planning/export.
 */
data class PortableSnapshotData(
    val entries: List<LibraryEntry>,
    val collections: List<Collection>,
    val collectionMembership: Map<String, Set<String>>,
    val smartCollections: List<SmartCollection>,
    val playbackByEntryId: Map<String, PlaybackCheckpoint>,
    val studyBundlesByEntryId: Map<String, EntryStudyBundle>,
    val offlineAssetsByEntryId: Map<String, List<OfflineAsset>>,
    val customProfiles: List<DownloadProfile>,
    val existingEntryIds: Set<String> = entries.map { it.id }.toSet(),
    val existingCollectionIds: Set<String> = collections.map { it.id }.toSet(),
    val existingSmartCollectionIds: Set<String> = smartCollections.map { it.id }.toSet(),
    val existingProfileIds: Set<String> = customProfiles.map { it.id }.toSet(),
    val existingProfileNamesLower: Set<String> = customProfiles.map { it.name.trim().lowercase() }.toSet(),
)

interface PortableBackupSnapshotSource {
    suspend fun captureConsistentSnapshot(): PortableSnapshotData
}

/**
 * Pure planner translating a [PortableSnapshotData] snapshot + resolved blob refs into a sanitized
 * [PortableBackupManifest]. Never embeds cookies, credentials, machine paths, host bindings, or
 * derived subtitle FTS/index caches.
 */
object PortableSnapshotCollector {

    fun assembleManifest(
        snapshot: PortableSnapshotData,
        bundleId: String,
        createdAtEpochMillis: Long,
        appVersion: String? = null,
        selectedEntryIds: Set<String>? = null,
        primaryBlobsByEntryId: Map<String, PortableBlobRef> = emptyMap(),
        assetBlobsByAssetId: Map<String, PortableBlobRef> = emptyMap(),
        skippedItems: List<PortableSkippedItem> = emptyList(),
    ): PortableBackupManifest {
        val chosenEntries = snapshot.entries
            .let { list ->
                if (selectedEntryIds == null) list else list.filter { it.id in selectedEntryIds }
            }
            .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.id }))

        val includedEntryIds = chosenEntries.map { it.id }.toSet()

        val portableEntries = chosenEntries.map { entry ->
            val bundle = snapshot.studyBundlesByEntryId[entry.id]
            val studySnapshot = bundle?.let {
                PortableEntryStudySnapshot(
                    notes = it.notes.map { note ->
                        PortableStudyNote(
                            exportId = note.id,
                            timestampMillis = note.timestampMillis,
                            endMillis = note.endMillis,
                            body = note.body,
                            mediaRevision = note.mediaRevision,
                            createdAtEpochMillis = note.createdAtEpochMillis,
                            updatedAtEpochMillis = note.updatedAtEpochMillis,
                        )
                    },
                    bookmarks = it.bookmarks.map { bm ->
                        PortableStudyBookmark(
                            exportId = bm.id,
                            timestampMillis = bm.timestampMillis,
                            label = bm.label,
                            mediaRevision = bm.mediaRevision,
                            createdAtEpochMillis = bm.createdAtEpochMillis,
                            updatedAtEpochMillis = bm.updatedAtEpochMillis,
                        )
                    },
                    ranges = it.savedRanges.map { range ->
                        PortableStudyRange(
                            exportId = range.id,
                            startMillis = range.startMillis,
                            endMillis = range.endMillis,
                            label = range.label,
                            mediaRevision = range.mediaRevision,
                            createdAtEpochMillis = range.createdAtEpochMillis,
                            updatedAtEpochMillis = range.updatedAtEpochMillis,
                        )
                    },
                    isStudied = it.studyState.isStudied,
                    studiedAtEpochMillis = it.studyState.studiedAtEpochMillis,
                    updatedAtEpochMillis = it.studyState.updatedAtEpochMillis,
                ).takeUnless { snap -> snap.isEmpty }
            }

            val checkpoint = snapshot.playbackByEntryId[entry.id]?.let { cp ->
                PortablePlaybackCheckpoint(
                    positionMillis = cp.positionMillis.coerceAtLeast(0L),
                    durationMillis = cp.durationMillis.coerceAtLeast(0L),
                )
            }

            val portableAssets = snapshot.offlineAssetsByEntryId[entry.id]
                .orEmpty()
                .sortedBy { it.id }
                .mapNotNull { asset ->
                    val blob = assetBlobsByAssetId[asset.id]
                    if (blob == null && asset.isRequired) {
                        // Still allow metadata when explicitly listed in skippedItems
                        PortableOfflineAssetRecord(
                            exportId = asset.id,
                            kind = asset.kind,
                            language = asset.language,
                            sourceType = asset.sourceType,
                            isRequired = asset.isRequired,
                            isPinned = asset.isPinned,
                            mediaRevision = asset.mediaRevision,
                            createdAtEpochMillis = asset.createdAtEpochMillis,
                            blob = null,
                        )
                    } else if (blob != null) {
                        PortableOfflineAssetRecord(
                            exportId = asset.id,
                            kind = asset.kind,
                            language = asset.language,
                            sourceType = asset.sourceType,
                            isRequired = asset.isRequired,
                            isPinned = asset.isPinned,
                            mediaRevision = asset.mediaRevision,
                            createdAtEpochMillis = asset.createdAtEpochMillis,
                            blob = blob,
                        )
                    } else {
                        null
                    }
                }

            val sanitizedThumbUrl = PortableArchivePathRules.sanitizeExternalUrlOrNull(entry.thumbnailUrl)

            PortableEntryRecord(
                exportId = entry.id,
                title = entry.title.trim().ifBlank { "Untitled" },
                kind = entry.kind,
                author = entry.author?.trim()?.takeIf { it.isNotBlank() },
                source = entry.source?.trim()?.takeIf { it.isNotBlank() } ?: "local",
                thumbnailUrl = sanitizedThumbUrl,
                createdAtEpochMillis = entry.createdAtEpochMillis,
                sizeBytes = primaryBlobsByEntryId[entry.id]?.byteCount ?: entry.sizeBytes,
                tags = entry.tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                primaryMedia = primaryBlobsByEntryId[entry.id],
                playbackCheckpoint = checkpoint,
                study = studySnapshot,
                offlineAssets = portableAssets,
            )
        }

        val portableCollections = snapshot.collections
            .sortedBy { it.id }
            .mapNotNull { collection ->
                val refs = snapshot.collectionMembership[collection.id]
                    .orEmpty()
                    .filter { it in includedEntryIds }
                    .sorted()
                if (selectedEntryIds != null && refs.isEmpty()) {
                    null
                } else {
                    PortableCollectionRecord(
                        exportId = collection.id,
                        name = collection.name.trim().ifBlank { "Collection" },
                        entryExportIds = refs,
                    )
                }
            }

        val portableSmart = snapshot.smartCollections
            .sortedBy { it.id }
            .map { smart ->
                PortableSmartCollectionRecord(
                    exportId = smart.id,
                    name = smart.name.trim().ifBlank { "Smart" },
                    query = smart.query,
                    kind = smart.kind,
                    tag = smart.tag,
                    minSizeBytes = smart.minSizeBytes,
                    maxSizeBytes = smart.maxSizeBytes,
                    createdWithinDays = smart.createdWithinDays,
                )
            }

        val portableProfiles = snapshot.customProfiles
            .filter { !it.isBuiltIn && DownloadProfile.BuiltIns.none { builtIn -> builtIn.id == it.id } }
            .sortedBy { it.id }
            .map { profile ->
                PortableDownloadProfileRecord(
                    exportId = profile.id,
                    profile = profile.copy(isBuiltIn = false),
                )
            }

        return PortableBackupManifest(
            formatVersion = PortableBackupFormat.FORMAT_VERSION,
            minReaderVersion = PortableBackupFormat.MIN_SUPPORTED_READER_VERSION,
            bundleId = bundleId,
            createdAtEpochMillis = createdAtEpochMillis,
            appVersion = appVersion,
            privacyNotice = PortableBackupFormat.PLAINTEXT_PRIVACY_MARKER,
            entries = portableEntries,
            collections = portableCollections,
            smartCollections = portableSmart,
            downloadProfiles = portableProfiles,
            skippedItems = skippedItems,
        )
    }
}
