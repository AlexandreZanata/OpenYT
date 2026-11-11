package com.openyt.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInstanceTest {
    @Test fun linksReachTheExistingInstanceWithoutOpeningAnotherDatabaseOwner() {
        val root = Files.createTempDirectory("openyt-instance-").toFile()
        try {
            assertNotNull(DesktopInstance.acquire(root, true)).use { first ->
                assertNull(DesktopInstance.acquire(root, true))
                assertTrue(DesktopInstance.forward(root, "https://example.com/vídeo?a=1&b=2"))
                assertEquals(listOf("https://example.com/vídeo?a=1&b=2"), first.takeLinks())
                assertTrue(first.takeLinks().isEmpty())
                assertTrue(DesktopInstance.forward(root, null))
                assertEquals(listOf<String?>(null), first.takeLinks())
                assertFalse(DesktopInstance.forward(root, "file:///private"))
            }
            assertNotNull(DesktopInstance.acquire(root, false)).use {
                assertFalse(DesktopInstance.forward(root, "https://example.com"))
            }
        } finally { root.deleteRecursively() }
    }
}
