package com.openyt.android.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.openyt.core.application.PickedSubtitle
import com.openyt.core.application.SubtitlePicker
import java.io.ByteArrayOutputStream

/**
 * Reads an explicitly chosen SRT/VTT document over a scoped URI grant and copies
 * the bytes into the app-owned asset store through [PickedSubtitle].
 */
class AndroidSubtitlePicker(
    private val activity: ComponentActivity,
) : SubtitlePicker {

    private var pendingCallback: ((PickedSubtitle?) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = pendingCallback
        pendingCallback = null
        callback?.invoke(uri?.let(::readSubtitle))
    }

    override val canPick: Boolean = true

    override fun pickSubtitle(onPicked: (PickedSubtitle?) -> Unit) {
        pendingCallback = onPicked
        launcher.launch(
            arrayOf(
                "application/x-subrip",
                "application/x-subtitle",
                "text/vtt",
                "text/plain",
                "application/octet-stream",
            ),
        )
    }

    private fun readSubtitle(uri: Uri): PickedSubtitle? {
        val resolver = activity.contentResolver
        val filename = displayName(resolver, uri) ?: uri.lastPathSegment ?: "subtitle.srt"
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SUBTITLE_BYTES) return null
                    output.write(buffer, 0, read)
                }
                if (total == 0L) return null
                PickedSubtitle(filename = filename, bytes = output.toByteArray())
            }
        }.getOrNull()
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
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

    private companion object {
        const val MAX_SUBTITLE_BYTES = 10L * 1024 * 1024
    }
}
