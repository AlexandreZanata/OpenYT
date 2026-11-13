package com.openyt.core.application

import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.SmartCollection
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote

data class PlannedPortableAsset(
    val sourceArchivePath: String,
    val expectedSha256: String,
    val expectedByteCount: Long,
    val originalFileName: String,
    val asset: OfflineAsset,
)

data class PlannedPortableEntryImport(
    val sourceExportId: String,
    val targetEntryId: String,
    val primaryBlob: PortableBlobRef?,
    val entryTemplate: LibraryEntry,
    val playbackCheckpoint: PlaybackCheckpoint?,
    val notes: List<StudyNote>,
    val bookmarks: List<StudyBookmark>,
    val savedRanges: List<SavedStudyRange>,
    val studyState: EntryStudyState?,
    val assets: List<PlannedPortableAsset>,
)

data class PortableRemappedImportPlan(
    val conflictPolicy: PortableRestoreConflictPolicy,
    val entryIdMap: Map<String, String>,
    val entriesToImport: List<PlannedPortableEntryImport>,
    val skippedDuplicateEntryIds: List<String>,
    val collections: List<Collection>,
    val collectionMembership: List<Pair<String, String>>,
    val smartCollections: List<SmartCollection>,
    val customProfiles: List<DownloadProfile>,
    val skippedProfileIds: List<String>,
) {
    val totalBlobBytesToCopy: Long
        get() = entriesToImport.sumOf { entry ->
            (entry.primaryBlob?.byteCount ?: 0L) + entry.assets.sumOf { it.expectedByteCount }
        }
}

/**
 * Pure deterministic ID remapper for portable backup restores.
 * Ensures zero overwrites of existing entries, collections, study rows, assets, or profiles
 * across all foreign keys.
 */
object PortableIdRemapper {

    fun buildImportPlan(
        manifest: PortableBackupManifest,
        existing: PortableSnapshotData,
        conflictPolicy: PortableRestoreConflictPolicy,
        nowEpochMillis: Long,
        idFactory: (prefix: String, sequence: Int) -> String = { prefix, seq ->
            "$prefix-restored-$nowEpochMillis-$seq"
        },
    ): PortableRemappedImportPlan {
        PortableBackupCodec.validateManifest(manifest).getOrThrow()

        var sequence = 0
        fun allocateUniqueId(prefix: String, rawCandidate: String, occupied: MutableSet<String>): String {
            val trimmed = rawCandidate.trim()
            if (trimmed.isNotBlank() && occupied.add(trimmed)) {
                return trimmed
            }
            while (true) {
                sequence++
                val candidate = idFactory(prefix, sequence)
                if (occupied.add(candidate)) return candidate
            }
        }

        val usedEntryIds = existing.existingEntryIds.toMutableSet()
        val existingShaToEntryId = existing.entries
            .mapNotNull { entry ->
                entry.mediaRevision?.contentSha256?.lowercase()?.let { sha -> sha to entry.id }
            }
            .toMap()

        val entryIdMap = mutableMapOf<String, String>()
        val skippedDuplicateEntryIds = mutableListOf<String>()
        val entriesToImport = mutableListOf<PlannedPortableEntryImport>()
        val usedAssetIds = existing.offlineAssetsByEntryId.values.flatten().map { it.id }.toMutableSet()
        val usedNoteIds = existing.studyBundlesByEntryId.values.flatMap { it.notes }.map { it.id }.toMutableSet()
        val usedBookmarkIds = existing.studyBundlesByEntryId.values.flatMap { it.bookmarks }.map { it.id }.toMutableSet()
        val usedRangeIds = existing.studyBundlesByEntryId.values.flatMap { it.savedRanges }.map { it.id }.toMutableSet()

        manifest.entries.forEach { sourceEntry ->
            val primarySha = sourceEntry.primaryMedia?.sha256?.lowercase()
            val matchingExistingEntryId = if (primarySha != null) existingShaToEntryId[primarySha] else null
            if (conflictPolicy == PortableRestoreConflictPolicy.SKIP_IF_PRESENT &&
                (matchingExistingEntryId != null || sourceEntry.exportId in usedEntryIds)
            ) {
                skippedDuplicateEntryIds += sourceEntry.exportId
                // Map collections pointing to this skipped exportId only if exact SHA matched existing entry
                if (matchingExistingEntryId != null) {
                    entryIdMap[sourceEntry.exportId] = matchingExistingEntryId
                } else if (sourceEntry.exportId in usedEntryIds) {
                    entryIdMap[sourceEntry.exportId] = sourceEntry.exportId
                }
                return@forEach
            }

            val targetEntryId = allocateUniqueId("entry", sourceEntry.exportId, usedEntryIds)
            entryIdMap[sourceEntry.exportId] = targetEntryId

            val revision = sourceEntry.primaryMedia?.let { blob ->
                MediaRevision(
                    sizeBytes = blob.byteCount,
                    lastModifiedEpochMillis = blob.lastModifiedEpochMillis ?: nowEpochMillis,
                    contentSha256 = blob.sha256.lowercase(),
                )
            }

            val entryTemplate = LibraryEntry(
                id = targetEntryId,
                title = sourceEntry.title,
                kind = sourceEntry.kind,
                filePath = "",
                sizeBytes = sourceEntry.primaryMedia?.byteCount ?: sourceEntry.sizeBytes,
                createdAtEpochMillis = sourceEntry.createdAtEpochMillis.takeIf { it > 0L } ?: nowEpochMillis,
                thumbnailUrl = PortableArchivePathRules.sanitizeExternalUrlOrNull(sourceEntry.thumbnailUrl),
                source = sourceEntry.source,
                tags = sourceEntry.tags.distinct(),
                author = sourceEntry.author,
                mediaRevision = revision,
                lastVerifiedEpochMillis = if (revision != null) nowEpochMillis else null,
            )

            val checkpoint = sourceEntry.playbackCheckpoint?.let { cp ->
                PlaybackCheckpoint(
                    positionMillis = cp.positionMillis.coerceAtLeast(0L),
                    durationMillis = cp.durationMillis.coerceAtLeast(0L),
                    entryId = targetEntryId,
                )
            }

            val study = sourceEntry.study
            val notes = study?.notes.orEmpty().map { note ->
                StudyNote(
                    id = allocateUniqueId("note", note.exportId, usedNoteIds),
                    entryId = targetEntryId,
                    timestampMillis = note.timestampMillis,
                    endMillis = note.endMillis,
                    body = note.body,
                    mediaRevision = note.mediaRevision ?: revision,
                    createdAtEpochMillis = note.createdAtEpochMillis,
                    updatedAtEpochMillis = note.updatedAtEpochMillis,
                )
            }
            val bookmarks = study?.bookmarks.orEmpty().map { bm ->
                StudyBookmark(
                    id = allocateUniqueId("bm", bm.exportId, usedBookmarkIds),
                    entryId = targetEntryId,
                    timestampMillis = bm.timestampMillis,
                    label = bm.label,
                    mediaRevision = bm.mediaRevision ?: revision,
                    createdAtEpochMillis = bm.createdAtEpochMillis,
                    updatedAtEpochMillis = bm.updatedAtEpochMillis,
                )
            }
            val ranges = study?.ranges.orEmpty().map { range ->
                SavedStudyRange(
                    id = allocateUniqueId("range", range.exportId, usedRangeIds),
                    entryId = targetEntryId,
                    startMillis = range.startMillis,
                    endMillis = range.endMillis,
                    label = range.label,
                    mediaRevision = range.mediaRevision ?: revision,
                    createdAtEpochMillis = range.createdAtEpochMillis,
                    updatedAtEpochMillis = range.updatedAtEpochMillis,
                )
            }
            val studyState = study?.takeIf { it.isStudied || it.studiedAtEpochMillis != null }?.let {
                EntryStudyState(
                    entryId = targetEntryId,
                    isStudied = it.isStudied,
                    studiedAtEpochMillis = it.studiedAtEpochMillis,
                    updatedAtEpochMillis = it.updatedAtEpochMillis.takeIf { ts -> ts > 0L } ?: nowEpochMillis,
                )
            }

            val plannedAssets = sourceEntry.offlineAssets.mapNotNull { asset ->
                val blob = asset.blob ?: return@mapNotNull null
                val targetAssetId = allocateUniqueId("asset", asset.exportId, usedAssetIds)
                PlannedPortableAsset(
                    sourceArchivePath = blob.archivePath,
                    expectedSha256 = blob.sha256.lowercase(),
                    expectedByteCount = blob.byteCount,
                    originalFileName = blob.originalFileName,
                    asset = OfflineAsset(
                        id = targetAssetId,
                        entryId = targetEntryId,
                        kind = asset.kind,
                        language = asset.language,
                        sourceType = asset.sourceType,
                        relativeLocator = "",
                        byteCount = blob.byteCount,
                        sha256 = blob.sha256.lowercase(),
                        state = com.openyt.core.domain.AssetState.VERIFIED,
                        isRequired = asset.isRequired,
                        isPinned = asset.isPinned,
                        mediaRevision = revision ?: asset.mediaRevision,
                        createdAtEpochMillis = asset.createdAtEpochMillis.takeIf { it > 0L } ?: nowEpochMillis,
                        lastVerifiedEpochMillis = nowEpochMillis,
                        failureDetail = null,
                    ),
                )
            }

            entriesToImport += PlannedPortableEntryImport(
                sourceExportId = sourceEntry.exportId,
                targetEntryId = targetEntryId,
                primaryBlob = sourceEntry.primaryMedia,
                entryTemplate = entryTemplate,
                playbackCheckpoint = checkpoint,
                notes = notes,
                bookmarks = bookmarks,
                savedRanges = ranges,
                studyState = studyState,
                assets = plannedAssets,
            )
        }

        val usedCollectionIds = existing.existingCollectionIds.toMutableSet()
        val existingCollectionsByNameLower = existing.collections.associateBy { it.name.trim().lowercase() }
        val collectionsToInsert = mutableListOf<Collection>()
        val collectionMembershipPairs = mutableListOf<Pair<String, String>>()

        manifest.collections.forEach { sourceCollection ->
            val remappedMemberIds = sourceCollection.entryExportIds.mapNotNull { entryIdMap[it] }.distinct()
            if (remappedMemberIds.isEmpty() && sourceCollection.entryExportIds.isNotEmpty()) {
                return@forEach
            }
            val existingByName = existingCollectionsByNameLower[sourceCollection.name.trim().lowercase()]
            val targetCollectionId = if (conflictPolicy == PortableRestoreConflictPolicy.SKIP_IF_PRESENT && existingByName != null) {
                existingByName.id
            } else {
                val baseName = sourceCollection.name.trim().ifBlank { "Collection" }
                val targetName = if (existingByName == null) baseName else "$baseName (backup)"
                val newId = allocateUniqueId("col", sourceCollection.exportId, usedCollectionIds)
                collectionsToInsert += Collection(id = newId, name = targetName)
                newId
            }
            remappedMemberIds.forEach { targetEntryId ->
                collectionMembershipPairs += targetCollectionId to targetEntryId
            }
        }

        val usedSmartIds = existing.existingSmartCollectionIds.toMutableSet()
        val existingSmartNamesLower = existing.smartCollections.map { it.name.trim().lowercase() }.toMutableSet()
        val smartToInsert = mutableListOf<SmartCollection>()
        manifest.smartCollections.forEach { sourceSmart ->
            val lowerName = sourceSmart.name.trim().lowercase()
            if (conflictPolicy == PortableRestoreConflictPolicy.SKIP_IF_PRESENT && lowerName in existingSmartNamesLower) {
                return@forEach
            }
            val finalName = if (existingSmartNamesLower.add(lowerName)) {
                sourceSmart.name.trim()
            } else {
                val suffixed = "${sourceSmart.name.trim()} (backup)"
                existingSmartNamesLower.add(suffixed.lowercase())
                suffixed
            }
            smartToInsert += SmartCollection(
                id = allocateUniqueId("smart", sourceSmart.exportId, usedSmartIds),
                name = finalName,
                query = sourceSmart.query,
                kind = sourceSmart.kind,
                tag = sourceSmart.tag,
                minSizeBytes = sourceSmart.minSizeBytes,
                maxSizeBytes = sourceSmart.maxSizeBytes,
                createdWithinDays = sourceSmart.createdWithinDays,
            )
        }

        val usedProfileIds = (existing.existingProfileIds + DownloadProfile.BuiltIns.map { it.id }).toMutableSet()
        val usedProfileNamesLower = (existing.existingProfileNamesLower + DownloadProfile.BuiltIns.map { it.name.trim().lowercase() }).toMutableSet()
        val profilesToInsert = mutableListOf<DownloadProfile>()
        val skippedProfileIds = mutableListOf<String>()

        manifest.downloadProfiles.forEach { record ->
            val rawName = record.profile.name.trim()
            if (conflictPolicy == PortableRestoreConflictPolicy.SKIP_IF_PRESENT &&
                (record.profile.id in usedProfileIds || rawName.lowercase() in usedProfileNamesLower)
            ) {
                skippedProfileIds += record.exportId
                return@forEach
            }
            val targetId = allocateUniqueId("profile", record.profile.id, usedProfileIds)
            var candidateName = rawName
            var nameAttempt = 1
            while (!usedProfileNamesLower.add(candidateName.lowercase())) {
                candidateName = if (nameAttempt == 1) "$rawName (backup)" else "$rawName (backup $nameAttempt)"
                nameAttempt++
            }
            profilesToInsert += record.profile.copy(
                id = targetId,
                name = candidateName,
                isBuiltIn = false,
            )
        }

        return PortableRemappedImportPlan(
            conflictPolicy = conflictPolicy,
            entryIdMap = entryIdMap,
            entriesToImport = entriesToImport,
            skippedDuplicateEntryIds = skippedDuplicateEntryIds,
            collections = collectionsToInsert,
            collectionMembership = collectionMembershipPairs.distinct(),
            smartCollections = smartToInsert,
            customProfiles = profilesToInsert,
            skippedProfileIds = skippedProfileIds,
        )
    }
}
