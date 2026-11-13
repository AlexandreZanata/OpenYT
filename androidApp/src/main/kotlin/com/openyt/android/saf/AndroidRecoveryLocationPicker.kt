package com.openyt.android.saf

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.openyt.core.application.RecoveryLocationPickerPort

/**
 * Provides scoped SAF tree/document pickers for assisted library recovery without
 * requesting broad storage permissions.
 */
class AndroidRecoveryLocationPicker(
    private val activity: ComponentActivity,
) : RecoveryLocationPickerPort {

    private var pendingFolderCallback: ((List<String>) -> Unit)? = null
    private var pendingFilesCallback: ((List<String>) -> Unit)? = null

    private val openTreeLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val callback = pendingFolderCallback
        pendingFolderCallback = null
        if (uri != null) {
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            callback?.invoke(listOf(uri.toString()))
        } else {
            callback?.invoke(emptyList())
        }
    }

    private val openDocumentsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val callback = pendingFilesCallback
        pendingFilesCallback = null
        val picked = uris.map { it.toString() }
        callback?.invoke(picked)
    }

    override val canPickFolders: Boolean = true
    override val canPickFiles: Boolean = true

    override fun pickScanFolders(onPicked: (List<String>) -> Unit) {
        pendingFolderCallback = onPicked
        openTreeLauncher.launch(null)
    }

    override fun pickReplacementFiles(onPicked: (List<String>) -> Unit) {
        pendingFilesCallback = onPicked
        openDocumentsLauncher.launch(arrayOf("video/*", "audio/*", "application/octet-stream"))
    }
}
