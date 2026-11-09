package com.openyt.desktop.downloader

import java.io.File

/**
 * Resolves external tools without executing anything. An explicit environment
 * variable wins, then PATH, then platform-specific install locations.
 */
class ExecutableLocator {
    fun findYtDlp(): String? = find("OPENYT_YTDLP", listOf("yt-dlp"))

    fun findFfmpeg(): String? = find("OPENYT_FFMPEG", listOf("ffmpeg"))

    fun findFfprobe(): String? = find("OPENYT_FFPROBE", listOf("ffprobe"))

    fun findJsRuntime(): String? = find("OPENYT_JS_RUNTIME", listOf("deno", "node"))

    private fun find(envVar: String, names: List<String>): String? {
        System.getenv(envVar)?.let { candidate ->
            if (isExecutable(candidate)) return candidate
        }

        val path = System.getenv("PATH").orEmpty()
        for (directory in path.split(File.pathSeparatorChar)) {
            if (directory.isBlank()) continue
            for (name in names) {
                val candidates = if (isWindows) listOf("$name.exe", name) else listOf(name)
                for (candidate in candidates) {
                    val file = File(directory, candidate)
                    if (isExecutable(file.absolutePath)) return file.absolutePath
                }
            }
        }

        for (candidate in commonLocations(names)) {
            if (isExecutable(candidate)) return candidate
        }
        return null
    }

    private fun commonLocations(names: List<String>): List<String> {
        val home = System.getProperty("user.home").orEmpty()
        val prefixes = listOf("/usr/bin", "/usr/local/bin", "/opt/homebrew/bin", "$home/.local/bin")
        return prefixes.flatMap { prefix -> names.map { name -> File(prefix, name).absolutePath } }
    }

    private fun isExecutable(path: String): Boolean {
        val file = File(path)
        return file.isFile && (isWindows || file.canExecute())
    }

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
