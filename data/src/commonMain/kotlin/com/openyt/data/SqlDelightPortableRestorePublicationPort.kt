package com.openyt.data

import com.openyt.core.application.PortableRestoreCommitBundle
import com.openyt.core.application.PortableRestorePublicationPort
import com.openyt.core.application.ProfileDefinitionCodec
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes all remapped restore entities and marks the portable operation journal `SUCCEEDED`
 * inside a single atomic SQLite transaction.
 */
class SqlDelightPortableRestorePublicationPort(
    private val database: OpenYtDatabase,
) : PortableRestorePublicationPort {

    override suspend fun commitBundle(bundle: PortableRestoreCommitBundle) {
        withContext(Dispatchers.Default) {
            database.transaction {
                val queries = database.downloadQueries

                bundle.entries.forEach { entry ->
                    queries.insertLibraryEntry(
                        id = entry.id,
                        title = entry.title,
                        author = entry.author,
                        kind = entry.kind.name,
                        file_path = entry.filePath,
                        size_bytes = entry.sizeBytes,
                        thumbnail_url = entry.thumbnailUrl,
                        source = entry.source,
                        media_revision_size = entry.mediaRevision?.sizeBytes,
                        media_revision_mtime = entry.mediaRevision?.lastModifiedEpochMillis,
                        media_revision_sha256 = entry.mediaRevision?.contentSha256,
                        last_verified_at = entry.lastVerifiedEpochMillis,
                    )
                    queries.deleteLibraryTagsForEntry(entry.id)
                    entry.tags.forEach { tag ->
                        queries.insertLibraryTag(entry.id, tag)
                    }
                }

                bundle.playbackCheckpoints.forEach { (pathKey, cp) ->
                    queries.savePlaybackPosition(
                        path = pathKey,
                        position_millis = cp.positionMillis,
                        duration_millis = cp.durationMillis,
                        entry_id = cp.entryId,
                    )
                }

                bundle.notes.forEach { note ->
                    queries.insertPortableStudyNote(
                        id = note.id,
                        entry_id = note.entryId,
                        timestamp_ms = note.timestampMillis,
                        end_ms = note.endMillis,
                        body = note.body,
                        media_revision_size = note.mediaRevision?.sizeBytes,
                        media_revision_mtime = note.mediaRevision?.lastModifiedEpochMillis,
                        media_revision_sha256 = note.mediaRevision?.contentSha256,
                        created_at = note.createdAtEpochMillis,
                        updated_at = note.updatedAtEpochMillis,
                    )
                }

                bundle.bookmarks.forEach { bm ->
                    queries.insertPortableStudyBookmark(
                        id = bm.id,
                        entry_id = bm.entryId,
                        timestamp_ms = bm.timestampMillis,
                        label = bm.label,
                        media_revision_size = bm.mediaRevision?.sizeBytes,
                        media_revision_mtime = bm.mediaRevision?.lastModifiedEpochMillis,
                        media_revision_sha256 = bm.mediaRevision?.contentSha256,
                        created_at = bm.createdAtEpochMillis,
                        updated_at = bm.updatedAtEpochMillis,
                    )
                }

                bundle.savedRanges.forEach { range ->
                    queries.insertPortableStudyRange(
                        id = range.id,
                        entry_id = range.entryId,
                        start_ms = range.startMillis,
                        end_ms = range.endMillis,
                        label = range.label,
                        media_revision_size = range.mediaRevision?.sizeBytes,
                        media_revision_mtime = range.mediaRevision?.lastModifiedEpochMillis,
                        media_revision_sha256 = range.mediaRevision?.contentSha256,
                        created_at = range.createdAtEpochMillis,
                        updated_at = range.updatedAtEpochMillis,
                    )
                }

                bundle.studyStates.forEach { state ->
                    queries.upsertPortableEntryStudyState(
                        entry_id = state.entryId,
                        is_studied = if (state.isStudied) 1L else 0L,
                        studied_at = state.studiedAtEpochMillis,
                        updated_at = state.updatedAtEpochMillis,
                    )
                }

                bundle.offlineAssets.forEach { asset ->
                    queries.insertAsset(
                        id = asset.id,
                        entry_id = asset.entryId,
                        kind = asset.kind.name,
                        language = asset.language,
                        source_type = asset.sourceType,
                        relative_locator = asset.relativeLocator,
                        byte_count = asset.byteCount,
                        sha256 = asset.sha256,
                        state = asset.state.name,
                        is_required = if (asset.isRequired) 1L else 0L,
                        is_pinned = if (asset.isPinned) 1L else 0L,
                        media_revision_size = asset.mediaRevision.sizeBytes,
                        media_revision_mtime = asset.mediaRevision.lastModifiedEpochMillis,
                        media_revision_sha256 = asset.mediaRevision.contentSha256,
                        created_at = asset.createdAtEpochMillis,
                        last_verified_at = asset.lastVerifiedEpochMillis,
                        failure_detail = asset.failureDetail,
                    )
                }

                bundle.collections.forEach { collection ->
                    queries.insertCollection(id = collection.id, name = collection.name)
                }

                bundle.collectionMembership.forEach { (collectionId, entryId) ->
                    queries.insertCollectionEntry(collection_id = collectionId, entry_id = entryId)
                }

                bundle.smartCollections.forEach { smart ->
                    queries.insertSmartCollection(
                        id = smart.id,
                        name = smart.name,
                        query = smart.query,
                        kind = smart.kind?.name,
                        tag = smart.tag,
                        min_size_bytes = smart.minSizeBytes,
                        max_size_bytes = smart.maxSizeBytes,
                        created_within_days = smart.createdWithinDays?.toLong(),
                    )
                }

                bundle.customProfiles.forEach { profile ->
                    queries.insertProfile(
                        id = profile.id,
                        name = profile.name,
                        is_builtin = 0L,
                        definition_json = ProfileDefinitionCodec.encode(profile.copy(isBuiltIn = false)),
                    )
                }

                val j = bundle.succeededJournal
                queries.insertPortableOperation(
                    id = j.id,
                    direction = j.direction.name,
                    status = j.status.name,
                    bundle_id = j.bundleId,
                    archive_locator = j.archiveLocator,
                    archive_sha256 = j.archiveSha256,
                    destination_root = j.destinationRoot,
                    staging_dir = j.stagingDir,
                    total_bytes = j.totalBytes,
                    completed_bytes = j.completedBytes,
                    entries_total = j.entriesTotal.toLong(),
                    entries_done = j.entriesDone.toLong(),
                    plan_json = j.planJson,
                    error = j.error,
                    created_at = j.createdAtEpochMillis,
                    updated_at = j.updatedAtEpochMillis,
                )
            }
        }
    }
}
