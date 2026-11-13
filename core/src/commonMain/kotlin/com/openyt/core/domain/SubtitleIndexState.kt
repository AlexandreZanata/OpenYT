package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SubtitleIndexState {
    PENDING,
    INDEXING,
    READY,
    STALE,
    FAILED;

    val isUsable: Boolean get() = this == READY || this == STALE
}
