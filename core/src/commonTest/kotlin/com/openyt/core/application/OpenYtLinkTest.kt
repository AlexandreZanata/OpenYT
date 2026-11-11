package com.openyt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenYtLinkTest {
    @Test
    fun handoffPreservesTheCompleteUrl() {
        val url = "https://example.com/ação?v=123&list=abc#time"
        assertEquals(url, OpenYtLink.parse(OpenYtLink.create(url)))
    }
    @Test
    fun malformedOrUnsafeLinksNeverReachTheEngine() {
        for (link in listOf("openyt://download?url=file%3A%2F%2Fetc%2Fpasswd", "openyt://download?url=%FF", "openyt://download?url=%", "openyt://download?url=a&url=b", "https://example.com")) {
            assertNull(OpenYtLink.parse(link))
        }
        assertNull(OpenYtLink.create("https://user:password@example.com/video"))
    }
}
