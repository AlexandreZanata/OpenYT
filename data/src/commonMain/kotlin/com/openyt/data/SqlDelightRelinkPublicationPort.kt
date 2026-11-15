package com.openyt.data

import com.openyt.core.application.RelinkCommitBundle
import com.openyt.core.application.RelinkCommitStats
import com.openyt.core.application.RelinkContentMode
import com.openyt.core.application.RelinkPublicationPort
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightRelinkPublicationPort(
    private val database: OpenYtDatabase,
) : RelinkPublicationPort {

    override suspend fun loadEntryById(entryId: String): LibraryEntry? = withContext(Dispatchers.IO) {
        val queries = database.downloadQueries
        val row = queries.selectLibraryEntryById(entryId).executeAsOneOrNull() ?: return@withContext null
        val tags = queries.selectAllLibraryTags().executeAsList()
            .filter { it.entry_id == entryId }
            .map { it.tag }
        val rev = if (row.media_revision_size != null && row.media_revision_mtime != null) {
            MediaRevision(
                sizeBytes = row.media_revision_size,
                lastModifiedEpochMillis = row.media_revision_mtime,
                contentSha256 = row.media_revision_sha256,
            )
        } else {
            null
        }
        LibraryEntry(
            id = row.id,
            title = row.title,
            author = row.author,
            kind = runCatching { MediaKind.valueOf(row.kind) }.getOrDefault(MediaKind.VIDEO),
            filePath = row.file_path,
            sizeBytes = row.size_bytes,
            createdAtEpochMillis = row.created_at,
            thumbnailUrl = row.thumbnail_url,
            source = row.source,
            tags = tags,
            mediaRevision = rev,
            lastVerifiedEpochMillis = row.last_verified_at,
        )
    }

    override suspend fun commitRelink(bundle: RelinkCommitBundle): RelinkCommitStats =
        withContext(Dispatchers.IO) {
            val queries = database.downloadQueries
            var flaggedAssets = 0
            var invalidatedSubtitles = false

            database.transaction {
                queries.relinkUpdateLibraryEntryLocation(
                    file_path = bundle.newPath,
                    size_bytes = bundle.newSizeBytes,
                    media_revision_size = bundle.newRevision.sizeBytes,
                    media_revision_mtime = bundle.newRevision.lastModifiedEpochMillis,
                    media_revision_sha256 = bundle.newRevision.contentSha256,
                    last_verified_at = bundle.verifiedAtEpochMillis,
                    id = bundle.entryId,
                )

                val existingByEntry = queries.selectPlaybackPositionByEntryId(bundle.entryId).executeAsList()
                val existingByOldPath = queries.selectPlaybackPosition(bundle.previousPath).executeAsOneOrNull()
                val bestCheckpoint = (existingByEntry + listOfNotNull(existingByOldPath))
                    .maxByOrNull { it.position_millis }

                queries.deletePlaybackPositionByEntryId(bundle.entryId)
                queries.relinkDeletePlaybackByPath(bundle.previousPath)
                if (bestCheckpoint != null) {
                    queries.savePlaybackPosition(
                        path = bundle.newPath,
                        position_millis = bestCheckpoint.position_millis,
                        duration_millis = bestCheckpoint.duration_millis,
                        entry_id = bundle.entryId,
                    )
                }

                if (bundle.contentMode == RelinkContentMode.CHANGED_CONTENT_REPLACEMENT) {
                    val sentinel = bundle.priorSentinelRevision
                    queries.relinkStampNullStudyNoteRevisions(
                        rev_size = sentinel.sizeBytes,
                        rev_mtime = sentinel.lastModifiedEpochMillis,
                        rev_sha = sentinel.contentSha256,
                        entry_id = bundle.entryId,
                    )
                    queries.relinkStampNullStudyBookmarkRevisions(
                        rev_size = sentinel.sizeBytes,
                        rev_mtime = sentinel.lastModifiedEpochMillis,
                        rev_sha = sentinel.contentSha256,
                        entry_id = bundle.entryId,
                    )
                    queries.relinkStampNullStudyRangeRevisions(
                        rev_size = sentinel.sizeBytes,
                        rev_mtime = sentinel.lastModifiedEpochMillis,
                        rev_sha = sentinel.contentSha256,
                        entry_id = bundle.entryId,
                    )

                    val assets = queries.selectAssetsForEntry(bundle.entryId).executeAsList()
                    flaggedAssets = assets.size
                    if (flaggedAssets > 0) {
                        queries.relinkMarkOfflineAssetsStale(bundle.entryId)
                    }

                    val tracks = queries.selectSubtitleTrackIndexesForEntry(bundle.entryId).executeAsList()
                    invalidatedSubtitles = tracks.isNotEmpty()
                    queries.deleteFtsCuesForEntry(bundle.entryId)
                    queries.deleteCuesForEntry(bundle.entryId)
                    queries.deleteSubtitleTrackIndexesForEntry(bundle.entryId)
                }

                val rec = bundle.succeededJournal
                queries.insertPortableOperation(
                    id = rec.id,
                    direction = rec.direction.name,
                    status = rec.status.name,
                    bundle_id = rec.bundleId,
                    archive_locator = rec.archiveLocator,
                    archive_sha256 = rec.archiveSha256,
                    destination_root = rec.destinationRoot,
                    staging_dir = rec.stagingDir,
                    total_bytes = rec.totalBytes,
                    completed_bytes = rec.completedBytes,
                    entries_total = rec.entriesTotal.toLong(),
                    entries_done = rec.entriesDone.toLong(),
                    plan_json = rec.planJson,
                    error = rec.error,
                    created_at = rec.createdAtEpochMillis,
                    updated_at = rec.updatedAtEpochMillis,
                )
            }

            RelinkCommitStats(
                staleAssetsFlagged = flaggedAssets,
                subtitleIndexesInvalidated = invalidatedSubtitles,
            )
        }
}
