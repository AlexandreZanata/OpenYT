package com.openyt.core.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @Test
    fun autoChecksOnlyOncePerProcess() = runTest {
        val checker = FakeChecker(update("1.3.0"))
        val controller = UpdateController(checker, "1.2.0", scope())

        controller.checkIfNeeded()
        controller.checkIfNeeded()
        advanceUntilIdle()

        assertEquals(1, checker.calls)
        assertIs<UpdateState.Available>(controller.state.value)
    }

    @Test
    fun filtersUpdatesWithoutPlatformAsset() = runTest {
        val update = update("1.3.0", assetName = "OpenYT.dmg")
        val controller = UpdateController(
            checker = FakeChecker(update),
            currentVersion = "1.2.0",
            scope = scope(),
            hasPlatformAsset = { info -> info.assets.any { it.name.endsWith(".apk") } },
        )

        controller.check()
        advanceUntilIdle()

        assertEquals(UpdateState.UpToDate, controller.state.value)
    }

    @Test
    fun reportsUpToDateAndFailures() = runTest {
        val upToDate = UpdateController(FakeChecker(null), "1.2.0", scope())
        upToDate.check()
        advanceUntilIdle()
        assertEquals(UpdateState.UpToDate, upToDate.state.value)

        val failed = UpdateController(FakeChecker(info = null, error = true), "1.2.0", scope())
        failed.check()
        advanceUntilIdle()
        assertEquals(UpdateState.Failed, failed.state.value)
    }

    @Test
    fun manualCheckRunsAgain() = runTest {
        val checker = FakeChecker(update("1.3.0"))
        val controller = UpdateController(checker, "1.2.0", scope())

        controller.checkIfNeeded()
        controller.check()
        advanceUntilIdle()

        assertEquals(2, checker.calls)
    }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private fun update(version: String, assetName: String = "app.apk") = UpdateInfo(
        version = version,
        releaseUrl = "https://github.com/o/r/releases/tag/v$version",
        assets = listOf(UpdateAsset(assetName, "https://github.com/o/r/download/$assetName")),
    )

    private class FakeChecker(
        private val info: UpdateInfo?,
        private val error: Boolean = false,
    ) : UpdateChecker {
        var calls: Int = 0
            private set

        override suspend fun latest(currentVersion: String): UpdateInfo? {
            calls++
            if (error) error("network")
            return info
        }
    }
}
