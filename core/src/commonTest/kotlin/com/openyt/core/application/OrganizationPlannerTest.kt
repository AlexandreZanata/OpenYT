package com.openyt.core.application

import com.openyt.core.domain.OrganizationPreset
import com.openyt.core.domain.PlaylistEntry
import com.openyt.core.domain.PlaylistInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganizationPlannerTest {
    @Test
    fun rejectsUnsafeOrIncompleteTemplates() {
        assertTrue(OrganizationPlanner.isValidTemplate("%(title).100B.%(ext)s"))
        assertFalse(OrganizationPlanner.isValidTemplate("../%(title)s.%(ext)s"))
        assertFalse(OrganizationPlanner.isValidTemplate("sub/%(title)s.%(ext)s"))
        assertFalse(OrganizationPlanner.isValidTemplate("sub\\%(title)s.%(ext)s"))
        assertFalse(OrganizationPlanner.isValidTemplate("%(title)s"))
        assertFalse(OrganizationPlanner.isValidTemplate("   "))
    }

    @Test
    fun singleTemplateUsesChannelOrYearFolders() {
        assertEquals(
            "%(uploader).80B/%(title).100B.%(ext)s",
            OrganizationPlanner.singleTemplate(
                OrganizationPreset.CHANNEL,
                "%(title).100B.%(ext)s",
            ),
        )
        assertEquals(
            "%(upload_date>%Y)s/%(title).100B.%(ext)s",
            OrganizationPlanner.singleTemplate(
                OrganizationPreset.YEAR,
                "%(title).100B.%(ext)s",
            ),
        )
        assertEquals(
            "%(title).100B.%(ext)s",
            OrganizationPlanner.singleTemplate(
                OrganizationPreset.PLAYLIST,
                "%(title).100B.%(ext)s",
            ),
        )
    }

    @Test
    fun playlistPresetsProduceSafeFoldersAndPrefixes() {
        val playlist = PlaylistInfo(
            sourceId = "p",
            source = "youtube",
            title = "../../My playlist",
            webpageUrl = "https://example.com/list",
            entries = listOf(PlaylistEntry(1, "One", "https://example.com/1")),
        )

        val channel = OrganizationPlanner.playlist(
            playlist,
            OrganizationPreset.PLAYLIST_CHANNEL,
            null,
        )
        assertEquals(listOf(".._.._My playlist"), channel.folderSegments)
        assertEquals("%(uploader).80B/", channel.templatePrefix)

        val year = OrganizationPlanner.playlist(playlist, OrganizationPreset.YEAR, null)
        assertEquals(emptyList(), year.folderSegments)
        assertEquals("%(upload_date>%Y)s/", year.templatePrefix)
    }
}
