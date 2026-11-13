package com.openyt.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDeepLinksTest {

    @Test
    fun linuxRegistrationAndSupportDetectionHonorActualXdgHandlerState() {
        val dir = Files.createTempDirectory("openyt-xdg-").toFile()
        val launcher = Files.createTempFile(dir.toPath(), "OpenYT", "").toFile().apply {
            writeText("#!/bin/sh\n")
        }
        try {
            assertFalse(
                DesktopDeepLinks.isEntryLinkDispatchSupported(
                    launcher = null,
                    xdgDataHome = dir.absolutePath,
                    osName = "Linux",
                    runCommand = { null },
                ),
            )

            val recordedCommands = mutableListOf<List<String>>()
            assertTrue(
                DesktopDeepLinks.register(
                    launcher = launcher.absolutePath,
                    xdgDataHome = dir.absolutePath,
                    osName = "Linux",
                    runCommand = { cmd ->
                        recordedCommands += cmd
                        ""
                    },
                ),
            )
            assertTrue(recordedCommands.any { it.contains("xdg-mime") && it.contains("x-scheme-handler/openyt") })

            assertTrue(
                DesktopDeepLinks.isEntryLinkDispatchSupported(
                    launcher = launcher.absolutePath,
                    xdgDataHome = dir.absolutePath,
                    osName = "Linux",
                    runCommand = { "openyt-link.desktop\n" },
                ),
            )

            assertFalse(
                DesktopDeepLinks.isEntryLinkDispatchSupported(
                    launcher = launcher.absolutePath,
                    xdgDataHome = dir.absolutePath,
                    osName = "Linux",
                    runCommand = { "other-app.desktop\n" },
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun windowsAndMacOsHostLinkSupportFollowsVerificationRules() {
        assertTrue(
            DesktopDeepLinks.isEntryLinkDispatchSupported(
                launcher = "C:\\Program Files\\OpenYT\\OpenYT.exe",
                osName = "Windows 11",
                runCommand = { "    (Default)    REG_SZ    \"C:\\Program Files\\OpenYT\\OpenYT.exe\" \"%1\"" },
            ),
        )
        assertFalse(
            DesktopDeepLinks.isEntryLinkDispatchSupported(
                launcher = null,
                osName = "Windows 11",
                runCommand = { null },
            ),
        )
        assertFalse(
            DesktopDeepLinks.isEntryLinkDispatchSupported(
                launcher = "/Applications/OpenYT.app/Contents/MacOS/OpenYT",
                osName = "Mac OS X",
                runCommand = { "" },
            ),
        )
    }

    @Test
    fun windowsRegistrationEmitsValidRegImportFileAndCommand() {
        val dir = Files.createTempDirectory("openyt-winreg-").toFile()
        val fakeExe = Files.createTempFile(dir.toPath(), "OpenYT", ".exe").toFile().apply { writeText("stub") }
        try {
            val content = DesktopDeepLinks.windowsRegistryImportContent("C:\\Program Files\\OpenYT\\OpenYT.exe")
            assertTrue(content.contains("[HKEY_CURRENT_USER\\Software\\Classes\\openyt]"))
            assertTrue(content.contains("@=\"URL:OpenYT\""))
            assertTrue(content.contains("\"URL Protocol\"=\"\""))
            assertTrue(content.contains("@=\"\\\"C:\\\\Program Files\\\\OpenYT\\\\OpenYT.exe\\\" \\\"%1\\\"\""))

            val recorded = mutableListOf<Pair<List<String>, ByteArray>>()
            assertTrue(
                DesktopDeepLinks.register(
                    launcher = fakeExe.absolutePath,
                    osName = "Windows 11",
                    runCommand = { cmd ->
                        val regFile = java.io.File(cmd.last())
                        recorded += cmd to regFile.readBytes()
                        ""
                    },
                ),
            )
            assertTrue(recorded.size == 1)
            val (cmd, bytes) = recorded.single()
            assertTrue(cmd[0] == "reg" && cmd[1] == "import" && cmd[2].endsWith(".reg"))
            assertTrue(bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
        } finally {
            dir.deleteRecursively()
        }
    }
}
