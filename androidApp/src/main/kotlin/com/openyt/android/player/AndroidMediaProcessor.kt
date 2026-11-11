package com.openyt.android.player

import android.content.Context
import com.openyt.core.application.MediaProcessor
import com.openyt.media.ffmpeg.FfmpegMediaProcessor
import com.yausername.ffmpeg.FFmpeg
import java.io.File

fun androidMediaProcessor(context: Context): MediaProcessor = FfmpegMediaProcessor(
    executable = {
        FFmpeg.getInstance().init(context.applicationContext)
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.isFile }?.absolutePath
    },
    processEnvironment = {
        mapOf("LD_LIBRARY_PATH" to File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath)
    },
)
