package com.openyt.android.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val mediaRuntimeMutex = Mutex()
@Volatile private var mediaRuntimeReady = false

/** All engine/processing ports share the upgrade policy before either singleton starts. */
internal suspend fun ensureAndroidMediaRuntime(context: Context) {
    if (mediaRuntimeReady) return
    mediaRuntimeMutex.withLock {
        if (mediaRuntimeReady) return@withLock
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
            val marker = File(app.noBackupFilesDir, "openyt-engine-app-version")
            if (!marker.isFile || marker.readText() != version) {
                val cache = File(app.noBackupFilesDir, "youtubedl-android/yt-dlp")
                check(!cache.exists() || cache.deleteRecursively())
                // Commit the cache policy before starting the wrapper singleton. A
                // marker write failure must never leave an initialized but deleted runtime.
                marker.writeText(version)
            }
            FFmpeg.getInstance().init(app)
            // FFmpeg's transitive libraries also live in the Python package.
            YoutubeDL.getInstance().init(app)
        }
        mediaRuntimeReady = true
    }
}

internal suspend fun androidFfmpegExecutable(context: Context): String? {
    ensureAndroidMediaRuntime(context)
    return File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.isFile }?.absolutePath
}

internal fun androidFfmpegEnvironment(context: Context): Map<String, String> {
    val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
    return mapOf(
        "LD_LIBRARY_PATH" to listOf("python", "ffmpeg").joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
        "TMPDIR" to context.cacheDir.absolutePath,
    )
}
