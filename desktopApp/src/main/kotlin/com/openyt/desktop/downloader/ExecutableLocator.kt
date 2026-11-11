package com.openyt.desktop.downloader

import java.io.File

/**
 * Resolves external tools without executing anything. An explicit environment
 * variable wins, then tools bundled with the package, then PATH, then
 * platform-specific install locations.
 */
class ExecutableLocator(
    private val bundledDirectory: () -> File? = { BundledTools.directory() },
) {
    fun findYtDlp(): String? = find("OPENYT_YTDLP", listOf("yt-dlp"))

    fun findFfmpeg(): String? = find("OPENYT_FFMPEG", listOf("ffmpeg"))

    fun findFfprobe(): String? = find("OPENYT_FFPROBE", listOf("ffprobe"))

    fun findJsRuntime(): String? = find("OPENYT_JS_RUNTIME", listOf("deno", "node"))

    /** Returns the `RUNTIME[:PATH]` value expected by `yt-dlp --js-runtimes`. */
    fun findJsRuntimeArgument(): String? {
        val path = findJsRuntime() ?: return null
        val name = File(path).nameWithoutExtension
        return if (name == "deno" || name == "node") "$name:$path" else null
    }

    private fun find(envVar: String, names: List<String>): String? {
        System.getenv(envVar)?.let { candidate ->
            if (isExecutable(candidate)) return candidate
        }

        if (envVar == "OPENYT_YTDLP") RuntimeToolUpdate.verifiedPath()?.let { return it }

        names.firstNotNullOfOrNull(::findBundled)?.let { return it }

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

    private fun findBundled(name: String): String? {
        val directory = bundledToolsDirectory() ?: return null
        val candidates = if (isWindows) listOf("$name.exe", name) else listOf(name)
        return candidates
            .map { File(directory, it) }
            .firstOrNull { isExecutable(it.absolutePath) }
            ?.absolutePath
    }

    private fun bundledToolsDirectory(): File? =
        System.getenv("OPENYT_BUNDLED_TOOLS_DIR")
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?: bundledDirectory()

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
