package com.openyt.android.downloader

import android.content.Context
import com.openyt.core.application.MediaTagger
import com.openyt.media.ffmpeg.FfmpegMediaTagger

class AndroidMediaTagger(context: Context) : MediaTagger by FfmpegMediaTagger(
    executable = { androidFfmpegExecutable(context) },
    processEnvironment = { androidFfmpegEnvironment(context) },
)
