package com.openyt.core.application

import com.openyt.core.domain.DownloadTask

fun interface SafExporter {
    suspend fun export(filePath: String, treeUri: String): Boolean
}

sealed interface SafExportResult {
    data class Exported(val treeUri: String, val filePath: String) : SafExportResult
    data class Skipped(val reason: String) : SafExportResult
    data class Warning(val treeUri: String, val filePath: String, val message: String) : SafExportResult
}

class SafExportRouter(
    private val exporter: SafExporter,
    private val fallbackTreeUri: () -> String? = { null },
    private val onWarning: ((SafExportResult.Warning) -> Unit)? = null,
) : CompletedDownloadHandler {
    override suspend fun onCompleted(task: DownloadTask) {
        val result = routeExport(task)
        if (result is SafExportResult.Warning) {
            onWarning?.invoke(result)
        }
    }

    suspend fun routeExport(task: DownloadTask): SafExportResult {
        val path = task.filePath ?: return SafExportResult.Skipped("Task has no file path")
        val targetUri = task.request.options.exportTargetUri ?: fallbackTreeUri()
            ?: return SafExportResult.Skipped("No SAF export target configured")

        val success = runCatching { exporter.export(path, targetUri) }.getOrDefault(false)
        return if (success) {
            SafExportResult.Exported(treeUri = targetUri, filePath = path)
        } else {
            SafExportResult.Warning(
                treeUri = targetUri,
                filePath = path,
                message = "SAF copy-export failed or tree grant was revoked for $targetUri; local download preserved at $path",
            )
        }
    }
}
