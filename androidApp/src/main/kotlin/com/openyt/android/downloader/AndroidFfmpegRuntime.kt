package com.openyt.android.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

internal fun androidFfmpegExecutable(context: Context): String? {
    FFmpeg.getInstance().init(context.applicationContext)
    // FFmpeg's transitive shared libraries also live in the wrapper's Python package.
    YoutubeDL.getInstance().init(context.applicationContext)
    return File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.isFile }?.absolutePath
}

internal fun androidFfmpegEnvironment(context: Context): Map<String, String> {
    val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
    return mapOf(
        "LD_LIBRARY_PATH" to listOf("python", "ffmpeg").joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
        "TMPDIR" to context.cacheDir.absolutePath,
    )
}
