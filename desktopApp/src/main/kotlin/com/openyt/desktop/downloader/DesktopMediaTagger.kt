package com.openyt.desktop.downloader

import com.openyt.core.application.MediaTagger
import com.openyt.media.ffmpeg.FfmpegMediaTagger

class DesktopMediaTagger(locator: ExecutableLocator = ExecutableLocator()) : MediaTagger by FfmpegMediaTagger({ locator.findFfmpeg() })
