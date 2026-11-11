package com.openyt.data

import java.io.File

/** yt-dlp's requested_subtitles retains the temporary name after moving sidecars. */
fun confirmedEngineOutput(declaredPath: String?, destination: File, temporary: File, sidecar: Boolean): File? {
    val declared = declaredPath?.let(::File) ?: return null
    val resolved = if (sidecar && declared.toPath().normalize().startsWith(temporary.toPath().normalize())) {
        File(destination, temporary.toPath().normalize().relativize(declared.toPath().normalize()).toString())
    } else declared
    return resolved.takeIf { it.isFile && it.length() > 0 }
}
