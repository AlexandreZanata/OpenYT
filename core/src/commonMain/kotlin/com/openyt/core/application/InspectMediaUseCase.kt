package com.openyt.core.application

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection

class InspectMediaUseCase(
    private val engine: DownloadEngine,
    private val validator: UrlValidator = UrlValidator(),
) {
    suspend operator fun invoke(url: String): EngineResult<Inspection> {
        return when (val validation = validator.validate(url)) {
            is UrlValidation.Invalid ->
                EngineResult.Failure(DownloadFailure.InvalidUrl(validation.reason))

            is UrlValidation.Valid -> engine.inspect(validation.url)
        }
    }
}
