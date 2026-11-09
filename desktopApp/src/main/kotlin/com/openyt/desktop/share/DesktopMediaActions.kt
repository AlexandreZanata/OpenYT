package com.openyt.desktop.share

import com.openyt.core.application.MediaActions
import com.openyt.core.domain.ShareableMedia
import java.awt.Desktop
import java.io.File

class DesktopMediaActions(
    private val desktop: Desktop? = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null,
) : MediaActions {
    override val canShare: Boolean = false
    override val canOpen: Boolean = true
    override val canReveal: Boolean = true

    override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit

    override fun open(media: ShareableMedia): Boolean {
        val file = File(media.path)
        if (!file.isFile) return false
        val opened = runCatching {
            desktop?.open(file)
            desktop != null
        }.getOrDefault(false)
        return opened || openPath(file.absolutePath)
    }

    override fun reveal(media: ShareableMedia): Boolean {
        val file = File(media.path)
        if (!file.exists()) return false
        val revealed = runCatching {
            desktop?.browseFileDirectory(file)
            desktop != null
        }.getOrDefault(false)
        return revealed || openPath(file.parentFile?.absolutePath ?: return false)
    }

    private fun openPath(path: String): Boolean = runCatching {
        if (System.getProperty("os.name").orEmpty().lowercase().contains("linux")) {
            ProcessBuilder("xdg-open", path).start()
            true
        } else {
            false
        }
    }.getOrDefault(false)
}
