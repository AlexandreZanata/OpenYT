package com.openyt.core.application

interface SubtitleContentPort {
    fun exists(path: String): Boolean

    fun inspect(path: String): FileMetadata?

    suspend fun readContent(path: String, maxBytes: Long = 10 * 1024 * 1024L): String?

    suspend fun computeDigest(path: String): String?
}
