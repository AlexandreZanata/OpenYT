package com.openyt.desktop

import com.openyt.core.application.DownloadNotifier
import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/** Desktop notifications are available only on hosts with an accessible tray. */
class DesktopDownloadNotifier : DownloadNotifier, AutoCloseable {
    private var icon: TrayIcon? = null

    override fun requestPermission() {
        SwingUtilities.invokeLater {
            if (icon != null || !SystemTray.isSupported()) return@invokeLater
            runCatching {
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().apply {
                    color = Color(0x0F, 0x76, 0x6E)
                    fillRoundRect(0, 0, 32, 32, 8, 8)
                    color = Color.WHITE
                    fillPolygon(intArrayOf(12, 24, 12), intArrayOf(8, 16, 24), 3)
                    dispose()
                }
                val created = TrayIcon(image, "OpenYT").apply { isImageAutoSize = true }
                SystemTray.getSystemTray().add(created)
                icon = created
            }
        }
    }

    override fun show(id: String, title: String, message: String, failed: Boolean) {
        SwingUtilities.invokeLater {
            icon?.displayMessage("OpenYT · $message", title,
                if (failed) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO)
        }
    }

    override fun close() {
        SwingUtilities.invokeLater { icon?.let { SystemTray.getSystemTray().remove(it) }; icon = null }
    }
}
