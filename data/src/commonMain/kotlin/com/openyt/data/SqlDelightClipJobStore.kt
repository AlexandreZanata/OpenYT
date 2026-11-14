package com.openyt.data

import com.openyt.core.application.ClipJobRecord
import com.openyt.core.application.ClipJobStatus
import com.openyt.core.application.ClipJobStore
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightClipJobStore(private val database: OpenYtDatabase) : ClipJobStore {
    private val queries = database.downloadQueries
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun get(id: String): ClipJobRecord? = withContext(Dispatchers.Default) {
        queries.selectClipJob(id).executeAsOneOrNull()?.let { json.decodeFromString<ClipJobRecord>(it.definition_json) }
    }
    override suspend fun save(record: ClipJobRecord) = withContext(Dispatchers.Default) {
        queries.upsertClipJob(record.id, record.request.sourceEntryId.orEmpty(), json.encodeToString(record))
        Unit
    }
    override suspend fun load(entryId: String): List<ClipJobRecord> = withContext(Dispatchers.Default) {
        queries.selectEntryClipJobs(entryId).executeAsList().map { json.decodeFromString<ClipJobRecord>(it.definition_json) }
    }
    override suspend fun interruptRunning() = withContext(Dispatchers.Default) {
        queries.selectAllClipJobs().executeAsList().forEach { row ->
            val record = json.decodeFromString<ClipJobRecord>(row.definition_json)
            if (record.status == ClipJobStatus.RUNNING) save(record.copy(status = ClipJobStatus.INTERRUPTED))
        }
    }
    override suspend fun complete(record: ClipJobRecord) = withContext(Dispatchers.Default) {
        require(record.status == ClipJobStatus.SUCCEEDED)
        val output = requireNotNull(record.output)
        database.transaction {
            val existing = queries.selectLibraryEntryById(output.id).executeAsOneOrNull()
            val source = record.request.sourceEntryId?.let { queries.selectLibraryEntryById(it).executeAsOneOrNull() }
            require(existing == null || existing.file_path == output.path) { "Derived entry ID collision" }
            if (existing == null) queries.insertLibraryEntry(id = output.id, title = output.title, author = source?.author, kind = output.kind.name,
                file_path = output.path, size_bytes = output.sizeBytes, thumbnail_url = null, source = source?.source ?: "openyt",
                media_revision_size = output.mediaRevision?.sizeBytes, media_revision_mtime = output.mediaRevision?.lastModifiedEpochMillis,
                media_revision_sha256 = output.mediaRevision?.contentSha256, last_verified_at = null)
            queries.upsertClipJob(record.id, record.request.sourceEntryId.orEmpty(), json.encodeToString(record))
        }
        Unit
    }
}
