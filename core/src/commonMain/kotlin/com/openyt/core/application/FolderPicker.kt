package com.openyt.core.application

/**
 * Lets the user choose a download directory. Desktop implementations open a native
 * folder chooser; platforms without a picker pass null and keep the host default.
 */
fun interface FolderPicker {
    /** Returns the selected absolute path, or null when the user cancels. */
    fun pickFolder(current: String?): String?
}
