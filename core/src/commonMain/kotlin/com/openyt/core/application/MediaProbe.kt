package com.openyt.core.application

import com.openyt.core.domain.MediaTechnicalInfo

/** Reads technical metadata from a local media file. Returns null on failure. */
fun interface MediaProbe {
    suspend fun probe(path: String): MediaTechnicalInfo?
}
