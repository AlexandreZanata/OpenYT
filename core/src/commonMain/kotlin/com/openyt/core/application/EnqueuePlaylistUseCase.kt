package com.openyt.core.application

import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.PlaylistEntry
import com.openyt.core.domain.PlaylistEntrySelection
import com.openyt.core.domain.PlaylistInfo

/**
 * Enqueues each selected playlist entry as its own download task, using a folder
 * named after the playlist and numbered file names.
 */
class EnqueuePlaylistUseCase(
    private val coordinator: DownloadCoordinator,
) {
    operator fun invoke(
        playlist: PlaylistInfo,
        entries: List<PlaylistEntry>,
        kind: MediaKind,
        downloadsDirectory: String,
        priority: DownloadPriority = DownloadPriority.NORMAL,
        overrides: Map<Int, PlaylistEntrySelection> = emptyMap(),
    ): List<String> {
        if (entries.isEmpty()) return emptyList()
        val directory = downloadsDirectory.trimEnd('/', '\\') + "/" + safeSegment(playlist.title)
        val width = maxOf(2, entries.maxOf { it.index }.toString().length)
        return entries.map { entry ->
            val prefix = entry.index.toString().padStart(width, '0')
            val selection = overrides[entry.index]
            coordinator.enqueue(
                media = MediaInfo(
                    sourceId = entry.url,
                    source = playlist.source,
                    title = entry.title,
                    author = entry.author ?: playlist.author,
                    durationSeconds = entry.durationSeconds,
                    thumbnailUrl = entry.thumbnailUrl ?: playlist.thumbnailUrl,
                    webpageUrl = entry.url,
                ),
                request = DownloadRequest(
                    url = entry.url,
                    kind = selection?.kind ?: kind,
                    formatId = selection?.formatId,
                    destinationDirectory = directory,
                    fileNameTemplate = "$prefix - %(title).150B [%(id)s].%(ext)s",
                ),
                priority = priority,
            )
        }
    }

    private fun safeSegment(value: String): String {
        val cleaned = buildString {
            value.trim().forEach { character ->
                append(
                    if (character in ILLEGAL || character.isISOControl()) '_' else character,
                )
            }
        }.trim().trimEnd('.', ' ')
        val name = cleaned.ifBlank { "Playlist" }.take(MAX_SEGMENT_LENGTH)
        return if (name.lowercase() in RESERVED_NAMES) "_$name" else name
    }

    private companion object {
        const val MAX_SEGMENT_LENGTH = 100
        const val ILLEGAL = "/\\:*?\"<>|"
        val RESERVED_NAMES = setOf(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
        )
    }
}
