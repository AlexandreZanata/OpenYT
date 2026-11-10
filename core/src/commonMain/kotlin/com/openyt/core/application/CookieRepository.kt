package com.openyt.core.application

/**
 * Stores browser cookies locally, encrypted at rest, and exposes a short-lived
 * plaintext file only while a download runs.
 */
interface CookieRepository {
    suspend fun isConfigured(): Boolean

    suspend fun importCookies(content: String)

    suspend fun clear()

    suspend fun createTemporaryFile(): String?

    suspend fun deleteTemporaryFile(path: String)
}
