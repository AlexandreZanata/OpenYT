package com.openyt.core.application

/** Tells whether a library file still exists on the host. */
fun interface FileAvailability {
    fun exists(path: String): Boolean
}
