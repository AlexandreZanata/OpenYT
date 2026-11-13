package com.openyt.core.application

import com.openyt.core.domain.IndexReconcileSummary
import com.openyt.core.domain.ParsedSubtitleTrack
import com.openyt.core.domain.SubtitleFormat
import com.openyt.core.domain.SubtitleIndexState
import com.openyt.core.domain.SubtitleParseException
import com.openyt.core.domain.SubtitleTrackIndexRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SubtitleIndexRebuilder(
    private val indexRepository: SubtitleIndexRepository,
    private val contentPort: SubtitleContentPort,
    private val batchSize: Int = 5000,
    private val maxTrackSizeBytes: Long = 10 * 1024 * 1024L,
) {

    /**
     * Rebuilds or builds an index revision for a subtitle track through a cancellable,
     * bounded process. Retains the previous usable revision until the new revision commits.
     */
    suspend fun reindexTrack(
        trackId: String,
        entryId: String,
        subtitlePath: String,
        language: String?,
        mediaRevisionToken: String,
        formatHint: SubtitleFormat? = null,
        timebaseOffsetMs: Long = 0L,
    ): Result<SubtitleTrackIndexRecord> {
        currentCoroutineContext().ensureActive()

        if (!contentPort.exists(subtitlePath)) {
            val failureMsg = "Subtitle file not found at path: $subtitlePath"
            indexRepository.saveTrackIndex(
                SubtitleTrackIndexRecord(
                    trackId = trackId,
                    entryId = entryId,
                    language = language,
                    mediaRevisionToken = mediaRevisionToken,
                    trackRevision = "unknown",
                    state = SubtitleIndexState.FAILED,
                    errorMessage = failureMsg,
                )
            )
            return Result.failure(IllegalStateException(failureMsg))
        }

        val metadata = contentPort.inspect(subtitlePath)
        if (metadata != null && metadata.sizeBytes > maxTrackSizeBytes) {
            val failureMsg = "Subtitle file exceeds maximum allowed size of $maxTrackSizeBytes bytes: ${metadata.sizeBytes}"
            indexRepository.saveTrackIndex(
                SubtitleTrackIndexRecord(
                    trackId = trackId,
                    entryId = entryId,
                    language = language,
                    mediaRevisionToken = mediaRevisionToken,
                    trackRevision = "unknown",
                    state = SubtitleIndexState.FAILED,
                    errorMessage = failureMsg,
                )
            )
            return Result.failure(IllegalStateException(failureMsg))
        }

        val trackRevision = contentPort.computeDigest(subtitlePath)
            ?: (metadata?.let { "${it.sizeBytes}-${it.lastModifiedEpochMillis}" } ?: "unknown")

        // Check if existing index is already READY and matching both media and track revisions
        val existing = indexRepository.getTrackIndex(trackId)
        if (existing != null &&
            existing.state == SubtitleIndexState.READY &&
            existing.mediaRevisionToken == mediaRevisionToken &&
            existing.trackRevision == trackRevision &&
            existing.totalCues > 0
        ) {
            return Result.success(existing)
        }

        val nextRevision = indexRepository.beginRevision(
            trackId = trackId,
            entryId = entryId,
            language = language,
            mediaRevisionToken = mediaRevisionToken,
            trackRevision = trackRevision,
        )

        var committed = false
        try {
            currentCoroutineContext().ensureActive()

            val rawContent = contentPort.readContent(subtitlePath, maxTrackSizeBytes)
            if (rawContent == null) {
                val errorMsg = "Unable to read subtitle content from path: $subtitlePath"
                indexRepository.abortRevision(trackId, nextRevision, errorMsg)
                return Result.failure(IllegalStateException(errorMsg))
            }

            currentCoroutineContext().ensureActive()

            val resolvedFormat = formatHint ?: SubtitleFormat.fromExtension(subtitlePath.substringAfterLast('.', ""))

            val parsedTrack: ParsedSubtitleTrack = try {
                TimedSubtitleParser.parse(
                    content = rawContent,
                    trackId = trackId,
                    entryId = entryId,
                    language = language,
                    formatHint = resolvedFormat,
                    timebaseOffsetMs = timebaseOffsetMs,
                )
            } catch (e: SubtitleParseException) {
                indexRepository.abortRevision(trackId, nextRevision, "Parse error: ${e.message}")
                return Result.failure(e)
            }

            // Persist cues in bounded batches, checking cancellation before each batch
            parsedTrack.cues.chunked(batchSize).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                indexRepository.persistCues(
                    cues = chunk,
                    trackId = trackId,
                    entryId = entryId,
                    revision = nextRevision,
                )
            }

            currentCoroutineContext().ensureActive()

            // Atomically commit revision and clean previous revision rows
            indexRepository.commitRevision(
                trackId = trackId,
                revision = nextRevision,
                totalCues = parsedTrack.totalCues,
                timebaseOffsetMs = timebaseOffsetMs,
            )
            committed = true

            val committedRecord = indexRepository.getTrackIndex(trackId)
                ?: SubtitleTrackIndexRecord(
                    trackId = trackId,
                    entryId = entryId,
                    language = language,
                    mediaRevisionToken = mediaRevisionToken,
                    trackRevision = trackRevision,
                    activeRevision = nextRevision,
                    state = SubtitleIndexState.READY,
                    totalCues = parsedTrack.totalCues,
                    timebaseOffsetMs = timebaseOffsetMs,
                )

            return Result.success(committedRecord)
        } catch (e: CancellationException) {
            if (!committed) {
                indexRepository.abortRevision(trackId, nextRevision, "Reindexing canceled")
            }
            throw e
        } catch (t: Throwable) {
            if (!committed) {
                indexRepository.abortRevision(trackId, nextRevision, t.message ?: "Reindexing failed")
            }
            return Result.failure(t)
        }
    }

    /**
     * Marks track indexes for an entry as STALE when primary media changes.
     */
    suspend fun invalidateForMediaChange(entryId: String) {
        indexRepository.invalidateEntry(entryId, SubtitleIndexState.STALE)
    }

    /**
     * Removes all index data for a track without touching media files.
     */
    suspend fun removeTrackIndex(trackId: String) {
        indexRepository.deleteTrackIndex(trackId)
    }

    /**
     * Removes all index data for an entry without touching media files.
     */
    suspend fun removeTrackIndexesForEntry(entryId: String) {
        indexRepository.deleteTrackIndexesForEntry(entryId)
    }

    /**
     * Recovers after application restart or crashes: reconciles incomplete INDEXING jobs
     * and purges any abandoned revision rows without touching source media.
     */
    suspend fun reconcileJournal(): IndexReconcileSummary {
        return indexRepository.cleanAbandonedRevisions()
    }
}
