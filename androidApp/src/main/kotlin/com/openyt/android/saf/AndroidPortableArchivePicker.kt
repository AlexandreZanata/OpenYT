package com.openyt.android.saf

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.openyt.core.application.PortableArchivePickerPort

/**
 * Registers SAF document launchers at Activity creation time so the user can
 * choose portable backup ZIP locations with temporary scoped grants and zero
 * persistent storage permissions.
 */
class AndroidPortableArchivePicker(
    activity: ComponentActivity,
) : PortableArchivePickerPort {

    private var pendingExportCallback: ((String?) -> Unit)? = null
    private var pendingRestoreCallback: ((String?) -> Unit)? = null

    private val createDocumentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val callback = pendingExportCallback
        pendingExportCallback = null
        callback?.invoke(uri?.toString())
    }

    private val openDocumentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = pendingRestoreCallback
        pendingRestoreCallback = null
        callback?.invoke(uri?.toString())
    }

    override val canPick: Boolean = true

    override fun pickExportLocation(
        suggestedFileName: String,
        onPicked: (String?) -> Unit,
    ) {
        pendingExportCallback = onPicked
        val safeName = if (suggestedFileName.endsWith(".zip", ignoreCase = true)) {
            suggestedFileName
        } else {
            "$suggestedFileName.openyt-backup.zip"
        }
        createDocumentLauncher.launch(safeName)
    }

    override fun pickRestoreArchive(onPicked: (String?) -> Unit) {
        pendingRestoreCallback = onPicked
        openDocumentLauncher.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }
}
