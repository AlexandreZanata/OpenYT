package com.openyt.core.application

data class AudioMetadata(
    val title: String,
    val artist: String? = null,
)

/**
 * Writes embedded metadata to a media file. Implementations must never rename or
 * delete the user's file; failures leave the original untouched.
 */
interface MediaTagger {
    suspend fun writeTags(
        path: String,
        metadata: AudioMetadata,
        coverUrl: String? = null,
    ): Boolean
}
