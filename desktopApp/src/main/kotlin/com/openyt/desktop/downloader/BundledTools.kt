package com.openyt.desktop.downloader

import com.openyt.desktop.BuildInfo
import java.io.File

/**
 * Copies the tools embedded in the package into a writable cache directory and
 * restores their executable bit. Native installers may drop file permissions.
 */
object BundledTools {
    private val names = listOf("yt-dlp", "ffmpeg", "ffprobe", "deno")
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Synchronized
    fun directory(): File? {
        val source = System.getProperty("compose.application.resources.dir")
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?: return null
        val suffix = if (windows) ".exe" else ""
        val tools = names.map { File(source, "$it$suffix") }.filter { it.isFile }
        if (tools.isEmpty()) return null

        val cache = cacheDirectory().apply { mkdirs() }
        return runCatching {
            for (tool in tools) {
                val target = File(cache, tool.name)
                if (!target.isFile || target.length() != tool.length()) {
                    tool.copyTo(target, overwrite = true)
                }
                target.setExecutable(true)
            }
            cache
        }.getOrNull()
    }

    private fun cacheDirectory(): File {
        System.getProperty("openyt.bundledToolsCache")?.let { return File(it) }
        val home = System.getProperty("user.home") ?: "."
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val base = when {
            os.contains("win") ->
                File(System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path, "OpenYT")
            os.contains("mac") -> File(home, "Library/Caches/OpenYT")
            else -> File(System.getenv("XDG_CACHE_HOME") ?: File(home, ".cache").path, "openyt")
        }
        return File(base, "tools/${BuildInfo.version}")
    }
}
