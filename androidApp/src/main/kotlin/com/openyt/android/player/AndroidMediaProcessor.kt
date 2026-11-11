package com.openyt.android.player

import android.content.Context
import com.openyt.core.application.MediaProcessor
import com.openyt.media.ffmpeg.FfmpegMediaProcessor
import com.openyt.android.downloader.androidFfmpegExecutable
import com.openyt.android.downloader.androidFfmpegEnvironment

fun androidMediaProcessor(context: Context): MediaProcessor = FfmpegMediaProcessor(
    executable = { androidFfmpegExecutable(context) },
    processEnvironment = { androidFfmpegEnvironment(context) },
)
