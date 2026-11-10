package com.openyt.core.application

import com.openyt.core.domain.OrganizationPreset
import com.openyt.core.domain.PlaylistInfo

data class PlaylistOrganization(
    val folderSegments: List<String>,
    val templatePrefix: String,
    val fileTemplate: String,
)

object OrganizationPlanner {
    const val DEFAULT_FILE_TEMPLATE = "%(title).150B [%(id)s].%(ext)s"

    private const val MAX_TEMPLATE_LENGTH = 200
    private const val MAX_SEGMENT_LENGTH = 100
    private const val ILLEGAL = "/\\:*?\"<>|"
    private val RESERVED_NAMES = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    fun isValidTemplate(template: String): Boolean {
        val value = template.trim()
        return value.isNotEmpty() &&
            value.length <= MAX_TEMPLATE_LENGTH &&
            value.none { it == '/' || it == '\\' } &&
            !value.contains("..") &&
            value.contains("%(ext)s")
    }

    fun fileTemplate(custom: String?): String =
        custom?.trim()?.takeIf(::isValidTemplate) ?: DEFAULT_FILE_TEMPLATE

    fun singleTemplate(preset: OrganizationPreset, custom: String?): String {
        val file = fileTemplate(custom)
        return when (preset) {
            OrganizationPreset.PLAYLIST -> file
            OrganizationPreset.CHANNEL,
            OrganizationPreset.PLAYLIST_CHANNEL,
            -> "%(uploader).80B/$file"

            OrganizationPreset.YEAR,
            OrganizationPreset.PLAYLIST_YEAR,
            -> "%(upload_date>%Y)s/$file"
        }
    }

    fun playlist(
        playlist: PlaylistInfo,
        preset: OrganizationPreset,
        custom: String?,
    ): PlaylistOrganization {
        val file = fileTemplate(custom)
        val folder = sanitizeSegment(playlist.title)
        return when (preset) {
            OrganizationPreset.PLAYLIST ->
                PlaylistOrganization(listOf(folder), "", file)

            OrganizationPreset.PLAYLIST_CHANNEL ->
                PlaylistOrganization(listOf(folder), "%(uploader).80B/", file)

            OrganizationPreset.PLAYLIST_YEAR ->
                PlaylistOrganization(listOf(folder), "%(upload_date>%Y)s/", file)

            OrganizationPreset.CHANNEL ->
                PlaylistOrganization(emptyList(), "%(uploader).80B/", file)

            OrganizationPreset.YEAR ->
                PlaylistOrganization(emptyList(), "%(upload_date>%Y)s/", file)
        }
    }

    fun sanitizeSegment(value: String): String {
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
}
