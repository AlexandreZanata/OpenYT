package com.openyt.core.application

data class FetchedArtworkMetadata(
    val byteCount: Long,
    val mimeType: String,
    val formatExtension: String,
    val sha256: String,
)

/**
 * Port for fetching artwork image files from remote HTTP/HTTPS endpoints safely into
 * a target staging file.
 */
interface ArtworkFetchPort {

    /**
     * Streams artwork from [url] directly into [destinationPath].
     *
     * Invariants enforced:
     * - Only HTTP and HTTPS schemes.
     * - Embedded credentials in [url] are stripped before connection.
     * - Redirects are followed up to 5 times; redirect loops are detected and rejected.
     * - [maxSizeBytes] is strictly enforced during streaming; exceeding it fails with structured error.
     * - Image format headers are verified (JPEG, PNG, WEBP, GIF, BMP); invalid or corrupt payloads fail.
     * - SHA-256 digest is computed in streaming fashion without loading the entire payload into RAM.
     * - Responsive to coroutine cancellation, cleaning up [destinationPath] upon abort.
     */
    suspend fun fetchArtworkTo(
        url: String,
        destinationPath: String,
        maxSizeBytes: Long = DEFAULT_MAX_ARTWORK_BYTES,
    ): Result<FetchedArtworkMetadata>

    companion object {
        const val DEFAULT_MAX_ARTWORK_BYTES = 10_485_760L // 10 MiB
    }
}
