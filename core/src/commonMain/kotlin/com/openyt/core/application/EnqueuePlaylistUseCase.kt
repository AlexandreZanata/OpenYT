package com.openyt.core.application

import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.OrganizationPreset
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
        preset: OrganizationPreset = OrganizationPreset.PLAYLIST,
        customFileNameTemplate: String? = null,
    ): List<String> {
        if (entries.isEmpty()) return emptyList()
        val organization = OrganizationPlanner.playlist(playlist, preset, customFileNameTemplate)
        val directory = downloadsDirectory.trimEnd('/', '\\') +
            organization.folderSegments.joinToString("") { "/$it" }
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
                    url = playlist.webpageUrl.ifBlank { entry.url },
                    kind = selection?.kind ?: kind,
                    formatId = selection?.formatId,
                    destinationDirectory = directory,
                    fileNameTemplate = organization.templatePrefix +
                        "$prefix - " + organization.fileTemplate,
                    playlistItemIndex = entry.index,
                ),
                priority = priority,
            )
        }
    }
}
