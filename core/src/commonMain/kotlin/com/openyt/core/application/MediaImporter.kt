package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry

/**
 * Imports user-selected files through the platform document picker (Android SAF).
 * Implementations copy the selection into app-owned storage and return library
 * entries; no broad storage permission is required.
 */
interface MediaImporter {
    val canImport: Boolean

    fun pickAndImport(onImported: (List<LibraryEntry>) -> Unit)
}
