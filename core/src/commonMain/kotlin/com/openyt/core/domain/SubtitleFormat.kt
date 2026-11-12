package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SubtitleFormat(val extension: String) {
    SRT("srt"),
    VTT("vtt");

    companion object {
        fun fromExtension(ext: String?): SubtitleFormat? = when (ext?.lowercase()?.trimStart('.')) {
            "srt" -> SRT
            "vtt" -> VTT
            else -> null
        }
    }
}
