package com.openyt.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Registers only OpenYT's own URI scheme for the current user. */
object DesktopDeepLinks {
    fun register(
        launcher: String? = System.getProperty("jpackage.app-path"),
        xdgDataHome: String? = System.getenv("XDG_DATA_HOME"),
        userHome: String? = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
        runCommand: (List<String>) -> String? = ::executeCommand,
    ): Boolean {
        if (launcher == null || !File(launcher).isFile) return false
        val os = osName.lowercase()
        return runCatching {
            if (os.contains("win")) {
                // Use `reg import` with a UTF-16LE BOM `.reg` file so Java's Win32
                // ProcessImpl never splits nested double quotes inside `/d "\"<path>\" \"%1\""`.
                val regFile = Files.createTempFile("openyt-protocol-", ".reg").toFile()
                try {
                    val content = windowsRegistryImportContent(launcher)
                    val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                    regFile.writeBytes(bom + content.toByteArray(Charsets.UTF_16LE))
                    checkNotNull(runCommand(listOf("reg", "import", regFile.absolutePath)))
                    true
                } finally {
                    regFile.delete()
                }
            } else if (!os.contains("mac")) {
                val base = xdgDataHome ?: "${userHome.orEmpty()}/.local/share"
                val file = File(base, "applications/openyt-link.desktop")
                file.parentFile.mkdirs()
                file.writeText(desktopEntry(launcher))
                checkNotNull(runCommand(listOf("xdg-mime", "default", "openyt-link.desktop", "x-scheme-handler/openyt")))
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Checks whether this host actually has an OS-level `openyt://` URI handler
     * registered for dispatching clickable local study links. Unpackaged runs
     * and unverified hosts (such as macOS in headless CI) return false so the UI
     * never advertises clickable-link support prematurely.
     */
    fun isEntryLinkDispatchSupported(
        launcher: String? = System.getProperty("jpackage.app-path"),
        xdgDataHome: String? = System.getenv("XDG_DATA_HOME"),
        userHome: String? = System.getProperty("user.home"),
        osName: String = System.getProperty("os.name").orEmpty(),
        runCommand: (List<String>) -> String? = ::executeCommand,
    ): Boolean {
        val os = osName.lowercase()
        return when {
            os.contains("mac") -> false
            os.contains("win") -> {
                val output = runCommand(
                    listOf("reg", "query", "HKCU\\Software\\Classes\\openyt\\shell\\open\\command", "/ve"),
                ) ?: return false
                output.contains("OpenYT", ignoreCase = true) ||
                    (launcher != null && output.contains(launcher, ignoreCase = true))
            }
            else -> {
                val base = xdgDataHome ?: "${userHome.orEmpty()}/.local/share"
                val file = File(base, "applications/openyt-link.desktop")
                if (!file.isFile) return false
                val content = runCatching { file.readText() }.getOrNull().orEmpty()
                if (!content.contains("MimeType=x-scheme-handler/openyt;")) return false
                val defaultHandler = runCommand(
                    listOf("xdg-mime", "query", "default", "x-scheme-handler/openyt"),
                )?.trim()
                if (defaultHandler == null) {
                    launcher != null && File(launcher).isFile
                } else {
                    defaultHandler == "openyt-link.desktop" || defaultHandler.contains("openyt", ignoreCase = true)
                }
            }
        }
    }

    internal fun windowsRegistryImportContent(launcher: String): String {
        require(!launcher.contains('\n') && !launcher.contains('\r'))
        val escaped = launcher.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("Windows Registry Editor Version 5.00\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\openyt]\r\n")
            append("@=\"URL:OpenYT\"\r\n")
            append("\"URL Protocol\"=\"\"\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\openyt\\shell\\open\\command]\r\n")
            append("@=\"\\\"").append(escaped).append("\\\" \\\"%1\\\"\"\r\n")
        }
    }

    fun desktopEntry(launcher: String): String {
        require(!launcher.contains('\n') && !launcher.contains('\r'))
        val escaped = launcher.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").replace("$", "\\$").replace("`", "\\`")
        return """
            [Desktop Entry]
            Type=Application
            Name=OpenYT link handler
            Exec="$escaped" %u
            MimeType=x-scheme-handler/openyt;
            NoDisplay=true
            Terminal=false
        """.trimIndent() + "\n"
    }

    private fun executeCommand(arguments: List<String>): String? = runCatching {
        val process = ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() == 0) output else null
    }.getOrNull()
}
