package com.openyt.core.domain

data class LibraryEntry(
    val id: String,
    val title: String,
    val kind: MediaKind,
    val filePath: String,
    val sizeBytes: Long? = null,
    val createdAtEpochMillis: Long = 0,
    val thumbnailUrl: String? = null,
    val source: String? = null,
    val tags: List<String> = emptyList(),
    val author: String? = null,
)
