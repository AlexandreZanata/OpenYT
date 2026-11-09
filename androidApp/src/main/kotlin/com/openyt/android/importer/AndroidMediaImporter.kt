package com.openyt.android.importer

import androidx.activity.ComponentActivity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import com.openyt.core.application.MediaImporter
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import java.io.File
import java.util.UUID

class AndroidMediaImporter(
    private val activity: ComponentActivity,
    downloadsDirectory: String,
) : MediaImporter {
    private var pendingCallback: ((List<LibraryEntry>) -> Unit)? = null
    private val files = MediaFileImporter(activity, downloadsDirectory)

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val callback = pendingCallback
        pendingCallback = null
        if (uris.isEmpty()) {
            callback?.invoke(emptyList())
            return@registerForActivityResult
        }
        val entries = uris.mapNotNull { uri -> importUri(uri) }
        callback?.invoke(entries)
    }

    override val canImport: Boolean = true

    override fun pickAndImport(onImported: (List<LibraryEntry>) -> Unit) {
        pendingCallback = onImported
        launcher.launch(arrayOf("video/*", "audio/*"))
    }

    fun importUri(uri: Uri): LibraryEntry? = files.importUri(uri)
}

class MediaFileImporter(
    private val context: Context,
    private val downloadsDirectory: String,
) {
    fun importUri(uri: Uri): LibraryEntry? {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "imported"
        val sanitized = sanitizeFileName(displayName)
        val mime = resolver.getType(uri)
        val kind = when {
            mime?.startsWith("video/") == true -> MediaKind.VIDEO
            mime?.startsWith("audio/") == true -> MediaKind.AUDIO
            sanitized.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS -> MediaKind.AUDIO
            else -> MediaKind.VIDEO
        }

        val directory = File(downloadsDirectory, "imported").apply { mkdirs() }
        val target = uniqueTarget(directory, sanitized)
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
        } catch (e: Exception) {
            target.delete()
            return null
        }

        return LibraryEntry(
            id = "import-${UUID.randomUUID()}",
            title = target.nameWithoutExtension.take(200).ifBlank { "Imported media" },
            kind = kind,
            filePath = target.absolutePath,
            sizeBytes = target.length().takeIf { it > 0 },
            createdAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val safe = trimmed
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim('.', ' ')
            .take(120)
        return safe.ifBlank { "imported" }
    }

    private fun uniqueTarget(directory: File, fileName: String): File {
        val initial = File(directory, fileName)
        if (!initial.exists()) return initial
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidateName = if (extension.isBlank()) {
                "$base ($index)"
            } else {
                "$base ($index).$extension"
            }
            val candidate = File(directory, candidateName)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    }
}
