package com.openyt.desktop

import com.openyt.core.application.RecoveryLocationPickerPort
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

class DesktopRecoveryLocationPicker : RecoveryLocationPickerPort {
    override val canPickFolders: Boolean = !GraphicsEnvironment.isHeadless()
    override val canPickFiles: Boolean = !GraphicsEnvironment.isHeadless()

    override fun pickScanFolders(onPicked: (List<String>) -> Unit) {
        if (!canPickFolders) {
            onPicked(emptyList())
            return
        }
        val action = Runnable {
            val chooser = JFileChooser(File(System.getProperty("user.home") ?: ".")).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = true
                dialogTitle = "OpenYT — Select Folder(s) to Scan"
            }
            val result = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val files = chooser.selectedFiles.takeIf { it.isNotEmpty() }?.toList()
                    ?: listOfNotNull(chooser.selectedFile)
                files.filter { it.isDirectory }.map { it.absolutePath }
            } else {
                emptyList()
            }
            onPicked(result)
        }
        if (SwingUtilities.isEventDispatchThread()) action.run() else SwingUtilities.invokeLater(action)
    }

    override fun pickReplacementFiles(onPicked: (List<String>) -> Unit) {
        if (!canPickFiles) {
            onPicked(emptyList())
            return
        }
        val action = Runnable {
            val chooser = JFileChooser(File(System.getProperty("user.home") ?: ".")).apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = true
                fileFilter = FileNameExtensionFilter(
                    "Media files",
                    "mp4", "mkv", "webm", "mov", "avi", "m4v", "mp3", "m4a", "aac", "opus", "ogg", "wav", "flac",
                )
                dialogTitle = "OpenYT — Select Replacement Media"
            }
            val result = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val files = chooser.selectedFiles.takeIf { it.isNotEmpty() }?.toList()
                    ?: listOfNotNull(chooser.selectedFile)
                files.filter { it.isFile }.map { it.absolutePath }
            } else {
                emptyList()
            }
            onPicked(result)
        }
        if (SwingUtilities.isEventDispatchThread()) action.run() else SwingUtilities.invokeLater(action)
    }
}
