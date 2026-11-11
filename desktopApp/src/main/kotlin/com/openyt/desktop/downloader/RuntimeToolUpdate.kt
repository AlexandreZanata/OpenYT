package com.openyt.desktop.downloader

import com.openyt.data.VerifiedToolUpdate
import com.openyt.data.VerifiedReleaseDownloader
import java.io.File

object RuntimeToolUpdate {
    private val os = System.getProperty("os.name").lowercase()
    private val arm = System.getProperty("os.arch").contains("aarch64")
    val assetName: String = when {
        os.contains("win") -> "yt-dlp.exe"
        os.contains("mac") -> "yt-dlp_macos"
        arm -> "yt-dlp_linux_aarch64"
        else -> "yt-dlp_linux"
    }
    private fun target(): File {
        val home = System.getProperty("user.home")
        val base = when {
            os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "OpenYT")
            os.contains("mac") -> File(home, "Library/Caches/OpenYT")
            else -> File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "openyt")
        }
        return File(base, "tools/runtime/${if (os.contains("win")) "yt-dlp.exe" else "yt-dlp"}")
    }
    fun updater() = VerifiedToolUpdate(assetName) { target() }
    fun verifiedPath(): String? {
        val file = target()
        val digest = File(file.parentFile, "${file.name}.sha256").takeIf { it.isFile }?.readText()?.trim() ?: return null
        return file.takeIf { VerifiedReleaseDownloader.matchesChecksum(it, digest) && (os.contains("win") || it.canExecute()) }?.absolutePath
    }
}
