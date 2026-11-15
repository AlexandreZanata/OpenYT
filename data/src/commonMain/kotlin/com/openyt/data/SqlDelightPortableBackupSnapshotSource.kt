package com.openyt.data

import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PortableBackupSnapshotSource
import com.openyt.core.application.PortableSnapshotData
import com.openyt.core.application.ProfileDefinitionCodec
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
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Captures a single-transaction SQLDelight snapshot of all portable user data
 * without reading cookies, secrets, destination bindings, or derived subtitle FTS indexes.
 */
class SqlDelightPortableBackupSnapshotSource(
    private val database: OpenYtDatabase,
) : PortableBackupSnapshotSource {

    override suspend fun captureConsistentSnapshot(): PortableSnapshotData =
        withContext(Dispatchers.Default) {
            database.transactionWithResult {
                val queries = database.downloadQueries
                val tagsByEntryId = queries.selectAllLibraryTags()
                    .executeAsList()
                    .groupBy({ it.entry_id }, { it.tag })

                val entries = queries.selectAllLibrary().executeAsList().map { row ->
                    val revision = if (row.media_revision_size != null && row.media_revision_mtime != null) {
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
                        kind = parseMediaKind(row.kind),
                        filePath = row.file_path,
                        sizeBytes = row.size_bytes,
                        createdAtEpochMillis = row.created_at,
                        thumbnailUrl = row.thumbnail_url,
                        source = row.source,
                        tags = tagsByEntryId[row.id].orEmpty(),
                        author = row.author,
                        mediaRevision = revision,
                        lastVerifiedEpochMillis = row.last_verified_at,
                    )
                }

                val collections = queries.selectAllCollections { id, name, _ ->
                    Collection(id = id, name = name)
                }.executeAsList()

                val membership = queries.selectCollectionMembership { collectionId, entryId ->
                    collectionId to entryId
                }.executeAsList()
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, memberIds) -> memberIds.toSet() }

                val smartCollections = queries.selectAllSmartCollections().executeAsList().map { row ->
                    SmartCollection(
                        id = row.id,
                        name = row.name,
                        query = row.query,
                        kind = row.kind?.let(::parseMediaKind),
                        tag = row.tag,
                        minSizeBytes = row.min_size_bytes,
                        maxSizeBytes = row.max_size_bytes,
                        createdWithinDays = row.created_within_days?.toInt(),
                    )
                }

                val playbackRows = queries.selectPortablePlaybackPositions().executeAsList()
                val playbackByEntry = playbackRows
                    .filter { it.entry_id != null }
                    .associate { row ->
                        row.entry_id!! to PlaybackCheckpoint(
                            positionMillis = row.position_millis,
                            durationMillis = row.duration_millis,
                            entryId = row.entry_id,
                        )
                    }
                    .toMutableMap()
                val playbackByPath = playbackRows.associateBy { it.path }
                entries.forEach { entry ->
                    if (entry.id !in playbackByEntry && entry.filePath.isNotBlank()) {
                        playbackByPath[entry.filePath]?.let { row ->
                            playbackByEntry[entry.id] = PlaybackCheckpoint(
                                positionMillis = row.position_millis,
                                durationMillis = row.duration_millis,
                                entryId = entry.id,
                            )
                        }
                    }
                }

                val notesByEntry = queries.selectPortableStudyNotes().executeAsList()
                    .map { row ->
                        StudyNote(
                            id = row.id,
                            entryId = row.entry_id,
                            timestampMillis = row.timestamp_ms,
                            endMillis = row.end_ms,
                            body = row.body,
                            mediaRevision = revisionOrNull(
                                row.media_revision_size,
                                row.media_revision_mtime,
                                row.media_revision_sha256,
                            ),
                            createdAtEpochMillis = row.created_at,
                            updatedAtEpochMillis = row.updated_at,
                        )
                    }
                    .groupBy { it.entryId }

                val bookmarksByEntry = queries.selectPortableStudyBookmarks().executeAsList()
                    .map { row ->
                        StudyBookmark(
                            id = row.id,
                            entryId = row.entry_id,
                            timestampMillis = row.timestamp_ms,
                            label = row.label,
                            mediaRevision = revisionOrNull(
                                row.media_revision_size,
                                row.media_revision_mtime,
                                row.media_revision_sha256,
                            ),
                            createdAtEpochMillis = row.created_at,
                            updatedAtEpochMillis = row.updated_at,
                        )
                    }
                    .groupBy { it.entryId }

                val rangesByEntry = queries.selectPortableStudyRanges().executeAsList()
                    .map { row ->
                        SavedStudyRange(
                            id = row.id,
                            entryId = row.entry_id,
                            startMillis = row.start_ms,
                            endMillis = row.end_ms,
                            label = row.label,
                            mediaRevision = revisionOrNull(
                                row.media_revision_size,
                                row.media_revision_mtime,
                                row.media_revision_sha256,
                            ),
                            createdAtEpochMillis = row.created_at,
                            updatedAtEpochMillis = row.updated_at,
                        )
                    }
                    .groupBy { it.entryId }

                val studyStateByEntry = queries.selectPortableEntryStudyStates().executeAsList()
                    .associate { row ->
                        row.entry_id to EntryStudyState(
                            entryId = row.entry_id,
                            isStudied = row.is_studied == 1L,
                            studiedAtEpochMillis = row.studied_at,
                            updatedAtEpochMillis = row.updated_at,
                        )
                    }

                val entryMap = entries.associateBy { it.id }
                val allStudyEntryIds = (notesByEntry.keys + bookmarksByEntry.keys + rangesByEntry.keys + studyStateByEntry.keys)
                val studyBundlesByEntry = allStudyEntryIds.associateWith { entryId ->
                    EntryStudyBundle(
                        entryId = entryId,
                        entryExists = entryId in entryMap,
                        currentMediaRevision = entryMap[entryId]?.mediaRevision,
                        notes = notesByEntry[entryId].orEmpty(),
                        bookmarks = bookmarksByEntry[entryId].orEmpty(),
                        savedRanges = rangesByEntry[entryId].orEmpty(),
                        studyState = studyStateByEntry[entryId] ?: EntryStudyState(entryId = entryId),
                    )
                }

                val assetsByEntry = queries.selectAllAssets().executeAsList()
                    .map { row ->
                        OfflineAsset(
                            id = row.id,
                            entryId = row.entry_id,
                            kind = runCatching { AssetKind.valueOf(row.kind) }.getOrDefault(AssetKind.PRIMARY_MEDIA),
                            language = row.language,
                            sourceType = row.source_type,
                            relativeLocator = row.relative_locator,
                            byteCount = row.byte_count,
                            sha256 = row.sha256,
                            state = runCatching { AssetState.valueOf(row.state) }.getOrDefault(AssetState.PENDING),
                            isRequired = row.is_required == 1L,
                            isPinned = row.is_pinned == 1L,
                            mediaRevision = MediaRevision(
                                sizeBytes = row.media_revision_size,
                                lastModifiedEpochMillis = row.media_revision_mtime,
                                contentSha256 = row.media_revision_sha256,
                            ),
                            createdAtEpochMillis = row.created_at,
                            lastVerifiedEpochMillis = row.last_verified_at,
                            failureDetail = row.failure_detail,
                        )
                    }
                    .groupBy { it.entryId }

                val customProfiles = queries.selectAllProfiles().executeAsList()
                    .filter { it.is_builtin == 0L }
                    .mapNotNull { row -> ProfileDefinitionCodec.decode(row.definition_json).getOrNull() }
                    .filter { !it.isBuiltIn && DownloadProfile.BuiltIns.none { builtIn -> builtIn.id == it.id } }

                PortableSnapshotData(
                    entries = entries,
                    collections = collections,
                    collectionMembership = membership,
                    smartCollections = smartCollections,
                    playbackByEntryId = playbackByEntry,
                    studyBundlesByEntryId = studyBundlesByEntry,
                    offlineAssetsByEntryId = assetsByEntry,
                    customProfiles = customProfiles,
                )
            }
        }

    private fun parseMediaKind(raw: String): MediaKind =
        runCatching { MediaKind.valueOf(raw) }.getOrDefault(MediaKind.VIDEO)

    private fun revisionOrNull(size: Long?, mtime: Long?, sha256: String?): MediaRevision? =
        if (size != null && mtime != null) MediaRevision(size, mtime, sha256) else null
}
