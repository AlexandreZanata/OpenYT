package com.openyt.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/** Registers only OpenYT's own URI scheme for the current user. */
object DesktopDeepLinks {
    fun register(launcher: String? = System.getProperty("jpackage.app-path")): Boolean {
        if (launcher == null || !File(launcher).isFile) return false
        val os = System.getProperty("os.name").lowercase()
        return runCatching {
            if (os.contains("win")) {
                val key = "HKCU\\Software\\Classes\\openyt"
                run(listOf("reg", "add", key, "/ve", "/d", "URL:OpenYT", "/f"))
                run(listOf("reg", "add", key, "/v", "URL Protocol", "/d", "", "/f"))
                run(listOf("reg", "add", "$key\\shell\\open\\command", "/ve", "/d", "\"$launcher\" \"%1\"", "/f"))
            } else if (!os.contains("mac")) {
                val base = System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share"
                val file = File(base, "applications/openyt-link.desktop")
                file.parentFile.mkdirs()
                file.writeText(desktopEntry(launcher))
                run(listOf("xdg-mime", "default", "openyt-link.desktop", "x-scheme-handler/openyt"))
            }
            true
        }.getOrDefault(false)
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

    private fun run(arguments: List<String>) {
        val process = ProcessBuilder(arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        check(process.exitValue() == 0)
    }
}
