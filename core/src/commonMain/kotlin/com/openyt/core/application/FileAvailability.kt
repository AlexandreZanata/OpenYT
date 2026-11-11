package com.openyt.core.application

/** Tells whether a library file still exists on the host. */
fun interface FileAvailability {
    fun exists(path: String): Boolean
}

internal fun mediaPathKey(path: String): String {
    val windowsPath = WindowsAbsolutePath.matches(path) ||
        path.startsWith("\\\\") || path.startsWith("//")
    return if (windowsPath) path.replace('\\', '/').trimEnd('/').lowercase() else path
}

private val WindowsAbsolutePath = Regex("^[A-Za-z]:[\\\\/].*")
