package com.openyt.android.player

import android.content.Context
import com.openyt.core.application.MediaProcessor
import com.openyt.media.ffmpeg.FfmpegMediaProcessor
import com.openyt.android.downloader.androidFfmpegExecutable
import com.openyt.android.downloader.androidFfmpegEnvironment

fun androidMediaProcessor(context: Context, clipStore: com.openyt.core.application.ClipJobStore? = null,
    assetStore: com.openyt.core.application.OfflineAssetStore? = null,
    outputDirectory: () -> String? = { null }): MediaProcessor = FfmpegMediaProcessor(
    executable = { androidFfmpegExecutable(context) },
    processEnvironment = { androidFfmpegEnvironment(context) },
    clipStore = clipStore,
    previewRoot = java.io.File(context.cacheDir, "clip-previews"),
    outputDirectory = { outputDirectory()?.let { java.io.File(it) } },
    onClipReady = com.openyt.core.application.ClipCoverPublisher(assetStore, com.openyt.data.JvmMediaFileInspector())::publish,
)
