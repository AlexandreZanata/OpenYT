package com.openyt.core.application

fun interface ToolUpdater {
    /** Explicit user action only. Returns after checksum verification and atomic replacement. */
    suspend fun update(): Boolean
}
