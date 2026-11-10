package com.openyt.desktop

import java.io.File

internal fun defaultDownloadsDirectory(): String {
    val home = System.getProperty("user.home") ?: "."
    val preferred = File(home, "Downloads/OpenYT")
    val directory = if (preferred.mkdirs() || preferred.isDirectory) {
        preferred
    } else {
        File(System.getProperty("java.io.tmpdir"), "openyt-downloads")
    }
    directory.mkdirs()
    cleanStaleTemporaryFiles(directory)
    return directory.absolutePath
}

internal fun cleanStaleTemporaryFiles(directory: File) {
    if (!directory.isDirectory) return
    val cutoff = System.currentTimeMillis() - STALE_TEMP_MILLIS
    directory.listFiles { file ->
        file.isDirectory &&
            file.name.startsWith(".openyt-tmp-") &&
            file.lastModified() < cutoff
    }?.forEach { it.deleteRecursively() }
}

private const val STALE_TEMP_MILLIS = 7L * 24 * 60 * 60 * 1000
