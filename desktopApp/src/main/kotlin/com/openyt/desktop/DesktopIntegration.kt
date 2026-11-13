package com.openyt.desktop

import com.openyt.core.application.ShareTextParser
import com.openyt.core.application.StudyMarkdownExporter
import com.openyt.core.application.TextExportOutcome
import com.openyt.core.application.TextFileExporter
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopDropTarget(
    private val onUrlDropped: (String) -> Unit,
) : DropTarget() {
    override fun drop(event: DropTargetDropEvent) {
        try {
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val url = extractDroppedUrl(event.transferable)
            if (url != null) onUrlDropped(url)
            event.dropComplete(url != null)
        } catch (e: Exception) {
            event.dropComplete(false)
        }
    }
}

fun extractDroppedUrl(transferable: Transferable): String? {
    val text = when {
        transferable.isDataFlavorSupported(DataFlavor.stringFlavor) ->
            transferable.getTransferData(DataFlavor.stringFlavor) as? String

        transferable.isDataFlavorSupported(URI_LIST_FLAVOR) ->
            transferable.getTransferData(URI_LIST_FLAVOR) as? String

        else -> null
    }
    return ShareTextParser.firstUrl(text)
}

object DesktopFolderPicker {
    fun pick(current: String?): String? {
        val chooser = JFileChooser(
            current?.let(::File)?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home") ?: "."),
        )
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        chooser.dialogTitle = "OpenYT — choose download folder"
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null

        val selected = chooser.selectedFile ?: return null
        if (!selected.mkdirs() && !selected.isDirectory) return null
        if (!selected.canWrite()) return null
        return selected.absolutePath
    }
}

private val URI_LIST_FLAVOR = DataFlavor("text/uri-list;class=java.lang.String")

object DesktopSubtitlePicker {
    fun pick(): com.openyt.core.application.PickedSubtitle? {
        val chooser = JFileChooser(File(System.getProperty("user.home") ?: "."))
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "Subtitles (*.srt, *.vtt)",
            "srt",
            "vtt",
        )
        chooser.dialogTitle = "OpenYT — import subtitle"
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null

        val selected = chooser.selectedFile ?: return null
        if (!selected.isFile || selected.length() <= 0L) return null
        if (selected.length() > MAX_SUBTITLE_BYTES) return null
        return runCatching {
            com.openyt.core.application.PickedSubtitle(
                filename = selected.name,
                bytes = selected.readBytes(),
            )
        }.getOrNull()
    }

    private const val MAX_SUBTITLE_BYTES = 10L * 1024 * 1024
}

/**
 * Exports Markdown study notes on desktop through a native Swing save dialog,
 * preserving user cancellation and surfacing clean IO failures.
 */
object DesktopTextFileExporter : TextFileExporter {
    override val canExport: Boolean = true

    override suspend fun exportMarkdown(
        suggestedFilename: String,
        content: String,
    ): TextExportOutcome {
        val safeName = if (suggestedFilename.endsWith(".md", ignoreCase = true)) {
            suggestedFilename
        } else {
            StudyMarkdownExporter.suggestedFilename(suggestedFilename)
        }
        val selectedFile: File? = withContext(Dispatchers.IO) {
            var chosen: File? = null
            val action = {
                val initialDir = File(System.getProperty("user.home") ?: ".")
                val chooser = JFileChooser(initialDir)
                chooser.fileSelectionMode = JFileChooser.FILES_ONLY
                chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Markdown (*.md)", "md")
                chooser.dialogTitle = "OpenYT — export study notes"
                chooser.selectedFile = File(initialDir, safeName)
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val candidate = chooser.selectedFile
                    if (candidate != null) {
                        val finalFile = if (candidate.extension.isEmpty()) File(candidate.parentFile, "${candidate.name}.md") else candidate
                        val confirmed = if (!finalFile.exists()) {
                            true
                        } else {
                            JOptionPane.showConfirmDialog(
                                null,
                                "Replace existing file ${finalFile.name}?",
                                "OpenYT",
                                JOptionPane.YES_NO_OPTION,
                            ) == JOptionPane.YES_OPTION
                        }
                        if (confirmed) chosen = finalFile
                    }
                }
            }
            if (SwingUtilities.isEventDispatchThread()) {
                action()
            } else {
                SwingUtilities.invokeAndWait(action)
            }
            chosen
        }
        val target = selectedFile ?: return TextExportOutcome.Canceled
        return withContext(Dispatchers.IO) {
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(content, Charsets.UTF_8)
                TextExportOutcome.Exported(target.name)
            }.getOrElse { error ->
                TextExportOutcome.Failed(error.message ?: "Failed to write study notes")
            }
        }
    }
}
