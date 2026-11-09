package com.clipbau.core.application

import com.clipbau.core.domain.DownloadEngine
import com.clipbau.core.domain.DownloadFailure
import com.clipbau.core.domain.EngineResult
import com.clipbau.core.domain.MediaInfo

class InspectMediaUseCase(
    private val engine: DownloadEngine,
    private val validator: UrlValidator = UrlValidator(),
) {
    suspend operator fun invoke(url: String): EngineResult<MediaInfo> {
        return when (val validation = validator.validate(url)) {
            is UrlValidation.Invalid ->
                EngineResult.Failure(DownloadFailure.InvalidUrl(validation.reason))

            is UrlValidation.Valid -> engine.inspect(validation.url)
        }
    }
}
