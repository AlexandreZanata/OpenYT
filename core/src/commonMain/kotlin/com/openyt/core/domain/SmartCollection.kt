package com.openyt.core.domain

/**
 * A named, read-time filter. Membership is evaluated against the current library,
 * so entries can enter or leave automatically without storing membership rows.
 */
data class SmartCollection(
    val id: String,
    val name: String,
    val query: String = "",
    val kind: MediaKind? = null,
    val tag: String? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val createdWithinDays: Int? = null,
)
