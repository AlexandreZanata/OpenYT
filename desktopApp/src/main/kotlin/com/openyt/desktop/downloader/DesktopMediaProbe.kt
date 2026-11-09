package com.openyt.desktop.downloader

import com.openyt.core.application.MediaProbe
import com.openyt.core.domain.MediaTechnicalInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopMediaProbe(
    private val locator: ExecutableLocator = ExecutableLocator(),
) : MediaProbe {
    override suspend fun probe(path: String): MediaTechnicalInfo? = withContext(Dispatchers.IO) {
        val ffprobe = locator.findFfprobe() ?: return@withContext null
        val process = ProcessBuilder(
            ffprobe,
            "-v",
            "error",
            "-show_entries",
            "format=duration,bit_rate,format_name:stream=codec_type,codec_name,width,height,bit_rate",
            "-of",
            "json",
            path,
        ).start()

        val stdout = process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode == 0) FfprobeJsonParser.parse(stdout) else null
    }
}
