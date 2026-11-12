package com.openyt.core.application

data class PickedSubtitle(
    val filename: String,
    val bytes: ByteArray,
)

/**
 * Requests an explicit SRT/VTT selection through a platform picker. Implementations
 * copy the selected bytes into owned storage through [SubtitleImportService]; no
 * broad storage permission or idle scraping is involved.
 */
interface SubtitlePicker {
    val canPick: Boolean

    fun pickSubtitle(onPicked: (PickedSubtitle?) -> Unit)
}
