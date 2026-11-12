package com.openyt.core.application

data class FileMetadata(
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
)

/**
 * Port for inspecting media files and computing content digests off the UI thread.
 */
interface MediaFileInspector {
    fun exists(path: String): Boolean
    fun inspect(path: String): FileMetadata?
    suspend fun computeSha256(path: String): String?
}
