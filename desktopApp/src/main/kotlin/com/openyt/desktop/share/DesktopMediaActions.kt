package com.openyt.desktop.share

import com.openyt.core.application.MediaActions
import com.openyt.core.domain.ShareableMedia
import java.awt.Desktop
import java.io.File

class DesktopMediaActions(
    private val desktop: Desktop? = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null,
    private val platform: String = System.getProperty("os.name").orEmpty(),
    private val windowsOpen: (File) -> Boolean = WindowsShell::open,
    private val windowsReveal: (File) -> Boolean = WindowsShell::reveal,
) : MediaActions {
    override val canShare: Boolean = false
    override val canOpen: Boolean = true
    override val canReveal: Boolean = true

    override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit

    override fun open(media: ShareableMedia): Boolean {
        val file = File(media.path)
        if (!file.isFile) return false
        if (platform.contains("windows", true)) return runCatching { windowsOpen(file) }.getOrDefault(false)
        val opened = runCatching {
            desktop?.open(file)
            desktop != null
        }.getOrDefault(false)
        return opened || openPath(file.absolutePath)
    }

    override fun reveal(media: ShareableMedia): Boolean {
        val file = File(media.path)
        if (!file.exists()) return false
        if (platform.contains("windows", true)) return runCatching { windowsReveal(file) }.getOrDefault(false)
        val revealed = runCatching {
            if (desktop?.isSupported(Desktop.Action.BROWSE_FILE_DIR) == true) {
                desktop.browseFileDirectory(file)
                true
            } else false
        }.getOrDefault(false)
        val parent = file.absoluteFile.parentFile ?: return false
        return revealed || runCatching {
            desktop?.open(parent)
            desktop != null
        }.getOrDefault(false) || openPath(parent.absolutePath)
    }

    private fun openPath(path: String): Boolean = runCatching {
        if (platform.contains("linux", true)) {
            ProcessBuilder("xdg-open", path).start()
            true
        } else {
            false
        }
    }.getOrDefault(false)
}
