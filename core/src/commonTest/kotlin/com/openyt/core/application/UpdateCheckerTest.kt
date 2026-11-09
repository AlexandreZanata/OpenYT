package com.openyt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(Semver.isNewer("1.0.1", "1.0.0"))
        assertTrue(Semver.isNewer("v1.1.0", "1.0.9"))
        assertTrue(Semver.isNewer("2.0.0", "1.9.9"))
        assertFalse(Semver.isNewer("1.0.0", "1.0.0"))
        assertFalse(Semver.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun parsesGitHubLatestReleaseAndRejectsOlderVersions() {
        val body = """{"tag_name":"v1.2.0","html_url":"https://github.com/o/r/releases/tag/v1.2.0"}"""

        val update = GitHubReleaseParser.parse(body, "1.1.0")
        assertEquals("1.2.0", update?.version)
        assertEquals("https://github.com/o/r/releases/tag/v1.2.0", update?.releaseUrl)
        assertNull(GitHubReleaseParser.parse(body, "1.2.0"))
        assertNull(GitHubReleaseParser.parse("{}", "1.0.0"))
    }

    @Test
    fun comparesEqualVersionsWithPrefixAndSuffix() {
        assertFalse(Semver.isNewer("v1.0.0-beta", "1.0.0"))
        assertFalse(Semver.isNewer("1.0.0+build", "1.0.0"))
    }
}
