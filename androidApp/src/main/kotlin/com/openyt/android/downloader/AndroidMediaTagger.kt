package com.openyt.android.downloader

import android.content.Context
import com.openyt.core.application.MediaTagger
import com.openyt.media.ffmpeg.FfmpegMediaTagger
import com.yausername.ffmpeg.FFmpeg
import java.io.File

class AndroidMediaTagger(context: Context) : MediaTagger by FfmpegMediaTagger(
    executable = {
        FFmpeg.getInstance().init(context.applicationContext)
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.isFile }?.absolutePath
    },
    processEnvironment = {
        mapOf("LD_LIBRARY_PATH" to File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath)
    },
)
