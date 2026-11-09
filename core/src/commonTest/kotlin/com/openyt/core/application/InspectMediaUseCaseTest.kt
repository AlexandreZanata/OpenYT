package com.openyt.core.application

import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaInfo
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InspectMediaUseCaseTest {
    @Test
    fun rejectsInvalidUrlWithoutCallingEngine() = runTest {
        val engine = FakeDownloadEngine()
        val useCase = InspectMediaUseCase(engine)

        val result = useCase("file:///etc/passwd")

        val failure = assertIs<EngineResult.Failure>(result).failure
        assertIs<DownloadFailure.InvalidUrl>(failure)
        assertEquals(0, engine.inspectCalls)
    }

    @Test
    fun returnsEngineInspectionForValidUrl() = runTest {
        val engine = FakeDownloadEngine()
        val useCase = InspectMediaUseCase(engine)

        val result = useCase("https://example.com/watch?v=1")

        val media = assertIs<EngineResult.Success<MediaInfo>>(result).value
        assertEquals("Sample media", media.title)
        assertEquals(1, engine.inspectCalls)
    }

    @Test
    fun propagatesStructuredEngineFailure() = runTest {
        val engine = FakeDownloadEngine(
            inspection = EngineResult.Failure(DownloadFailure.DependencyMissing("ffmpeg")),
        )
        val useCase = InspectMediaUseCase(engine)

        val result = useCase("https://example.com/watch?v=1")

        val failure = assertIs<EngineResult.Failure>(result).failure
        assertIs<DownloadFailure.DependencyMissing>(failure)
        assertTrue(failure.component == "ffmpeg")
    }
}
