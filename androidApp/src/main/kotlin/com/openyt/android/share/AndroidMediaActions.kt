package com.openyt.android.share

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.openyt.core.application.MediaActions
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ShareableMedia
import java.io.File

class AndroidMediaActions(
    private val activity: Activity,
) : MediaActions {
    override val canShare: Boolean = true
    override val canOpen: Boolean = true
    override val canReveal: Boolean = false

    override fun share(media: List<ShareableMedia>, chooserTitle: String) {
        val files = media.filter { File(it.path).isFile }
        if (files.isEmpty()) return

        val uris = files.mapNotNull { item ->
            runCatching {
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    File(item.path),
                )
            }.getOrNull()
        }
        if (uris.isEmpty()) return

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(files.single())
                putExtra(Intent.EXTRA_STREAM, uris.single())
                putExtra(Intent.EXTRA_SUBJECT, files.single().title)
                putExtra(Intent.EXTRA_TEXT, files.single().title)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra(Intent.EXTRA_SUBJECT, files.first().title)
            }
        }

        val clip = ClipData.newRawUri(files.first().title, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        runCatching {
            activity.startActivity(Intent.createChooser(intent, chooserTitle))
        }
    }

    override fun open(media: ShareableMedia): Boolean {
        val file = File(media.path)
        if (!file.isFile) return false
        val uri = runCatching {
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file,
            )
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(media))
            clipData = ClipData.newRawUri(media.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    override fun reveal(media: ShareableMedia): Boolean = false

    private fun mimeOf(media: ShareableMedia): String {
        val extension = File(media.path).extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (media.kind) {
                MediaKind.VIDEO -> "video/*"
                MediaKind.AUDIO -> "audio/*"
                MediaKind.SUBTITLE -> "text/*"
            }
    }
}
