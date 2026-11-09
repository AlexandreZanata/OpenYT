package com.openyt.core.domain

data class LibraryEntry(
    val id: String,
    val title: String,
    val kind: MediaKind,
    val filePath: String,
    val sizeBytes: Long? = null,
)
