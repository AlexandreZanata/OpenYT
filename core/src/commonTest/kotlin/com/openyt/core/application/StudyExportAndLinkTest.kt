package com.openyt.core.application

import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyExportAndLinkTest {

    private val entry = LibraryEntry(
        id = "entry/1?x=2",
        title = "Kotlin Coroutines & Study [Ação] <script>alert(1)</script>",
        author = "Prof. ![track](https://evil.invalid/pixel.png) *Name*",
        kind = MediaKind.VIDEO,
        filePath = "/home/secret-user/private-videos/token=abc123/lesson.mp4",
        sizeBytes = 42_000L,
    )

    @Test
    fun entryLinksRoundTripEncodedIdAndTimestampWhileRejectingTampering() {
        val withTime = OpenYtEntryLink.create("entry-42:abc", 95_500L)
        assertEquals("openyt://library/entry/entry-42%3Aabc?t=95500", withTime)
        val parsedWithTime = OpenYtEntryLink.parse(withTime)
        assertEquals(OpenYtRoute.LibraryEntry("entry-42:abc", 95_500L), parsedWithTime)

        assertNull(OpenYtEntryLink.create("folder/entry-1", 0L), "Slashes must not produce invalid entry links")
        assertNull(OpenYtEntryLink.parse("openyt://library/entry/folder%2Fentry-1?t=0"), "Percent-encoded slashes must stay rejected after decoding")

        val noTime = OpenYtEntryLink.create("lesson-123", null)
        assertEquals("openyt://library/entry/lesson-123", noTime)
        assertEquals(OpenYtRoute.LibraryEntry("lesson-123", null), OpenYtEntryLink.parse(noTime))

        val viaRoute = OpenYtLink.parseRoute(noTime)
        assertEquals(OpenYtRoute.LibraryEntry("lesson-123", null), viaRoute)

        val invalidLinks = listOf(
            null,
            "",
            "openyt://library/entry/",
            "openyt://library/entry/..",
            "openyt://library/entry/.",
            "openyt://library/entry/a/b",
            "openyt://library/entry/abc?",
            "openyt://library/entry/abc?t=",
            "openyt://library/entry/abc?t=-1",
            "openyt://library/entry/abc?t=+100",
            "openyt://library/entry/abc?t=10&t=20",
            "openyt://library/entry/abc?t=10&extra=1",
            "openyt://library/entry/abc?x=10",
            "openyt://library/entry/abc?t=99999999999999",
            "openyt://library/entry/abc#fragment",
            "openyt://library/entry/abc?t=10#frag",
            "openyt://library/entry/%FF?t=10",
            "openyt://library/entry/abc\n?t=10",
            "openyt://library@evil.com/entry/abc",
            "openyt://other/entry/abc",
            "https://example.com/library/entry/abc",
            "openyt://library/entry/${"x".repeat(300)}?t=5",
        )
        for (bad in invalidLinks) {
            assertNull(OpenYtEntryLink.parse(bad), "Expected rejection for: $bad")
            if (bad == null || !bad.startsWith("openyt://download?", ignoreCase = true)) {
                assertNull(OpenYtLink.parseRoute(bad), "Expected parseRoute rejection for: $bad")
            }
        }
        assertNull(OpenYtEntryLink.create("", 0L))
        assertNull(OpenYtEntryLink.create("ok", -5L))
        assertNull(OpenYtEntryLink.create("ok", OpenYtEntryLink.MAX_TIMESTAMP_MILLIS + 1L))
        assertNull(OpenYtEntryLink.create("a".repeat(OpenYtEntryLink.MAX_ENTRY_ID_LENGTH + 1), 0L))
    }

    @Test
    fun exporterEscapesUntrustedMarkdownAndOmitsPathsAndTokens() {
        val safeEntry = entry.copy(id = "safe-entry-1")
        val note = StudyNote(
            id = "n-1",
            entryId = safeEntry.id,
            timestampMillis = 65_000L,
            endMillis = 125_000L,
            body = "Review [phishing](javascript:alert(1)) & <b>raw html</b>\nLine 2 ![px](https://tracker.invalid/a.png)",
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_000L,
        )
        val bookmark = StudyBookmark(
            id = "b-1",
            entryId = safeEntry.id,
            timestampMillis = 3_723_000L,
            label = "Key point *important* <img src=x>",
            createdAtEpochMillis = 2_000L,
            updatedAtEpochMillis = 2_000L,
        )
        val range = SavedStudyRange(
            id = "r-1",
            entryId = safeEntry.id,
            startMillis = 10_000L,
            endMillis = 15_000L,
            label = "Drill #1 `code`",
            createdAtEpochMillis = 3_000L,
            updatedAtEpochMillis = 3_000L,
        )
        val bundle = EntryStudyBundle(
            entryId = safeEntry.id,
            entryExists = true,
            currentMediaRevision = null,
            notes = listOf(note),
            bookmarks = listOf(bookmark),
            savedRanges = listOf(range),
            studyState = EntryStudyState(entryId = safeEntry.id, isStudied = true, studiedAtEpochMillis = 4_000L, updatedAtEpochMillis = 4_000L),
        )
        assertFalse("secret-token" in StudyMarkdownExporter.escapeMarkdownInline("cookie=token"))
        val bundleOutputCheck = StudyMarkdownExporter.exportEntryBundle(
            entry = safeEntry.copy(title = "Safe Study", source = "https://example.com/?token=secret-token"),
            bundle = bundle,
            options = StudyExportOptions(includeLocalLinks = false, language = AppLanguage.EN),
            hostCapability = StudyHostLinkCapability(supportsClickableEntryLinks = false),
        )
        assertFalse("secret-token" in bundleOutputCheck)

        val withoutHostSupport = StudyMarkdownExporter.exportEntryBundle(
            entry = safeEntry,
            bundle = bundle,
            options = StudyExportOptions(includeLocalLinks = true, language = AppLanguage.EN),
            hostCapability = StudyHostLinkCapability(supportsClickableEntryLinks = false),
        )
        assertFalse("openyt://library/entry/" in withoutHostSupport, "Must not advertise/emit clickable links on unverified host")
        assertTrue("[01:05 - 02:05]" in withoutHostSupport)
        assertTrue("[1:02:03]" in withoutHostSupport)
        assertTrue("[00:10 - 00:15]" in withoutHostSupport)
        assertFalse("/home/secret-user" in withoutHostSupport)
        assertFalse("secret-token" in withoutHostSupport)
        assertFalse("<script>" in withoutHostSupport)
        assertFalse("<img " in withoutHostSupport)
        assertFalse("[phishing](javascript:alert(1))" in withoutHostSupport)
        assertTrue("&lt;script&gt;alert\\(1\\)&lt;/script&gt;" in withoutHostSupport)
        assertTrue("\\[phishing\\]\\(javascript\\:alert\\(1\\)\\)" in withoutHostSupport)

        val withHostSupport = StudyMarkdownExporter.exportEntryBundle(
            entry = safeEntry,
            bundle = bundle,
            options = StudyExportOptions(includeLocalLinks = true, language = AppLanguage.PT_BR),
            hostCapability = StudyHostLinkCapability(supportsClickableEntryLinks = true),
        )
        assertTrue("[[01:05 - 02:05]](openyt://library/entry/safe-entry-1?t=65000)" in withHostSupport)
        assertTrue("[[1:02:03]](openyt://library/entry/safe-entry-1?t=3723000)" in withHostSupport)
        assertTrue("## Notas de estudo" in withHostSupport)
        assertTrue("- Estado de estudo: Estudado" in withHostSupport)
    }

    @Test
    fun suggestedFilenameSanitizesTraversalAndReservedWindowsNames() {
        val traversal = StudyMarkdownExporter.suggestedFilename("../../evil:name*?<|>file.mp4")
        assertFalse("/" in traversal)
        assertFalse("\\" in traversal)
        assertFalse(".." in traversal)
        assertTrue(traversal.endsWith("-study-notes.md"))

        assertEquals("openyt-entry-study-notes.md", StudyMarkdownExporter.suggestedFilename("CON"))
        assertEquals("openyt-entry-study-notes.md", StudyMarkdownExporter.suggestedFilename("nul"))
        assertEquals("openyt-entry-study-notes.md", StudyMarkdownExporter.suggestedFilename("   "))
        val maxLen = StudyMarkdownExporter.suggestedFilename("a".repeat(200))
        assertNotNull(maxLen)
        assertTrue(maxLen.length <= 80)
    }
}
