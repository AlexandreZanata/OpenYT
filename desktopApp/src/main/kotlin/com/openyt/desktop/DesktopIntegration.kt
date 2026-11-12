package com.openyt.desktop

import com.openyt.core.application.ShareTextParser
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.JFileChooser

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
