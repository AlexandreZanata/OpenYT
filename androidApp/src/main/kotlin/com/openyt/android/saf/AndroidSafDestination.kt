package com.openyt.android.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.openyt.core.application.SafDestinationPicker
import com.openyt.core.application.SafExporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSafDestinationPicker(
    private val activity: ComponentActivity,
) : SafDestinationPicker {
    private var pending: ((String?) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val callback = pending
        pending = null
        if (uri == null) {
            callback?.invoke(null)
            return@registerForActivityResult
        }
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        callback?.invoke(uri.toString())
    }

    override val canPick: Boolean = true

    override fun pick(onPicked: (String?) -> Unit) {
        pending = onPicked
        launcher.launch(null)
    }
}

class AndroidSafExporter(
    private val context: Context,
) : SafExporter {
    override suspend fun export(filePath: String, treeUri: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.isFile) return@withContext false
        runCatching {
            val tree = Uri.parse(treeUri)
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            val mime = mimeOf(file)
            val document = DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                mime,
                file.name,
            ) ?: return@runCatching false
            context.contentResolver.openOutputStream(document)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
}
