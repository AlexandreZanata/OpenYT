package com.openyt.core.domain

data class MediaTechnicalInfo(
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrateKbps: Int? = null,
    val container: String? = null,
)
