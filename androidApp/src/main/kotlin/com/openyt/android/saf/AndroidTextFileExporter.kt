package com.openyt.android.saf

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.openyt.core.application.StudyMarkdownExporter
import com.openyt.core.application.TextExportOutcome
import com.openyt.core.application.TextFileExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Exports Markdown study notes via Android SAF [ActivityResultContracts.CreateDocument].
 * Must be instantiated before [ComponentActivity.setContent] / lifecycle STARTED state.
 */
class AndroidTextFileExporter(
    private val activity: ComponentActivity,
) : TextFileExporter {

    private val mutex = Mutex()
    private var pendingUri: CompletableDeferred<android.net.Uri?>? = null

    private val createDocumentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        pendingUri?.complete(uri)
        pendingUri = null
    }

    override val canExport: Boolean = true

    override suspend fun exportMarkdown(
        suggestedFilename: String,
        content: String,
    ): TextExportOutcome = mutex.withLock {
        val safeName = if (suggestedFilename.endsWith(".md", ignoreCase = true)) {
            suggestedFilename
        } else {
            StudyMarkdownExporter.suggestedFilename(suggestedFilename)
        }
        val deferred = CompletableDeferred<android.net.Uri?>()
        pendingUri = deferred
        withContext(Dispatchers.Main.immediate) {
            createDocumentLauncher.launch(safeName)
        }
        val uri = deferred.await() ?: return@withLock TextExportOutcome.Canceled
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = activity.contentResolver.openOutputStream(uri)
                    ?: error("Destination stream unavailable")
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(content)
                }
                val label = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: safeName
                TextExportOutcome.Exported(label)
            }.getOrElse { error ->
                TextExportOutcome.Failed(error.message ?: "Failed to write exported study notes")
            }
        }
    }
}
