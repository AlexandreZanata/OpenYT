package com.openyt.desktop

import com.openyt.core.application.PortableArchivePickerPort
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

class DesktopPortableArchivePicker : PortableArchivePickerPort {
    override val canPick: Boolean = !GraphicsEnvironment.isHeadless()

    override fun pickExportLocation(
        suggestedFileName: String,
        onPicked: (String?) -> Unit,
    ) {
        if (!canPick) {
            onPicked(null)
            return
        }
        val runPicker = Runnable {
            val initialDir = File(System.getProperty("user.home") ?: ".")
            val safeName = if (suggestedFileName.endsWith(".zip", ignoreCase = true)) {
                suggestedFileName
            } else {
                "$suggestedFileName.openyt-backup.zip"
            }
            val chooser = JFileChooser(initialDir).apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = FileNameExtensionFilter("OpenYT Portable Backup (*.zip)", "zip")
                dialogTitle = "OpenYT — Export Portable Backup"
                selectedFile = File(initialDir, safeName)
            }
            val approved = chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION
            val chosen = if (approved) {
                chooser.selectedFile?.let { file ->
                    if (file.name.endsWith(".zip", ignoreCase = true)) {
                        file.absolutePath
                    } else {
                        File(file.parentFile, "${file.name}.openyt-backup.zip").absolutePath
                    }
                }
            } else {
                null
            }
            onPicked(chosen)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            runPicker.run()
        } else {
            SwingUtilities.invokeLater(runPicker)
        }
    }

    override fun pickRestoreArchive(onPicked: (String?) -> Unit) {
        if (!canPick) {
            onPicked(null)
            return
        }
        val runPicker = Runnable {
            val initialDir = File(System.getProperty("user.home") ?: ".")
            val chooser = JFileChooser(initialDir).apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = FileNameExtensionFilter("OpenYT Portable Backup (*.zip)", "zip")
                dialogTitle = "OpenYT — Select Portable Backup"
            }
            val chosen = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.takeIf { it.isFile }?.absolutePath
            } else {
                null
            }
            onPicked(chosen)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            runPicker.run()
        } else {
            SwingUtilities.invokeLater(runPicker)
        }
    }
}
