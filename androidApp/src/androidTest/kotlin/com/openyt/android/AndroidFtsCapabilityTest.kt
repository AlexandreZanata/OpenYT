package com.openyt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import com.openyt.data.createOpenYtDriver
import com.openyt.data.search.ProbeCue
import com.openyt.data.search.SqliteFtsCapabilityProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidFtsCapabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "openyt-fts-probe-test.db"
    private lateinit var driver: SqlDriver
    private lateinit var probe: SqliteFtsCapabilityProbe

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        driver = createOpenYtDriver(context, databaseName)
        probe = SqliteFtsCapabilityProbe(
            sqlDriver = driver,
            driverName = "AndroidSqliteDriver (Framework SQLite)",
            osName = "Android API ${android.os.Build.VERSION.SDK_INT}",
            architecture = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        )
    }

    @After
    fun tearDown() {
        driver.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun androidSqliteVersionAndFts4Creation() {
        val sqliteVer = probe.probeSqliteVersion()
        assertTrue("SQLite version must be reported", sqliteVer.isNotBlank())

        val created = probe.createTable("android_fts_probe", "unicode61")
        assertTrue("FTS4 virtual table with unicode61 should be supported on Android", created)
        probe.dropTable("android_fts_probe")
    }

    @Test
    fun androidAccentFoldingAndPortugueseTokenization() {
        val tableName = "android_accent_probe"
        probe.createTable(tableName, "unicode61")

        val cues = listOf(
            ProbeCue(entryId = "a1", trackId = "t1", startMs = 0, endMs = 2000, plainText = "A persistência e a dedicação são essenciais."),
            ProbeCue(entryId = "a1", trackId = "t1", startMs = 2000, endMs = 4000, plainText = "Aprendizado de música com violão e órgão."),
            ProbeCue(entryId = "a1", trackId = "t1", startMs = 4000, endMs = 6000, plainText = "Não há dúvida sobre a eficiência técnica."),
            ProbeCue(entryId = "a2", trackId = "t1", startMs = 6000, endMs = 8000, plainText = "Café da manhã com açúcar e resumo."),
        )
        probe.insertBatch(tableName, cues)

        // Accent folding checks
        assertEquals(1, probe.search(tableName, "persistencia").size)
        assertEquals(1, probe.search(tableName, "persistência").size)
        assertEquals(1, probe.search(tableName, "musica").size)
        assertEquals(1, probe.search(tableName, "música").size)
        assertEquals(1, probe.search(tableName, "orgao").size)
        assertEquals(1, probe.search(tableName, "órgão").size)
        assertEquals(1, probe.search(tableName, "nao").size)
        assertEquals(1, probe.search(tableName, "não").size)
        assertEquals(1, probe.search(tableName, "cafe").size)
        assertEquals(1, probe.search(tableName, "café").size)

        probe.dropTable(tableName)
    }

    @Test
    fun androidIncrementalUpdatesAndCrud() {
        val tableName = "android_crud_probe"
        probe.createTable(tableName, "unicode61")

        val cue = ProbeCue(
            entryId = "android-crud",
            trackId = "track-1",
            startMs = 1000,
            endMs = 3000,
            plainText = "Texto original Android para teste",
        )
        val rowId = probe.insertCue(tableName, cue)
        assertEquals(1, probe.count(tableName))
        assertEquals(1, probe.search(tableName, "original").size)

        probe.updateCue(tableName, rowId, "Texto atualizado no Android com modificação")
        assertEquals(0, probe.search(tableName, "original").size)
        assertEquals(1, probe.search(tableName, "atualizado").size)
        assertEquals(1, probe.search(tableName, "modificacao").size)

        probe.deleteCue(tableName, rowId)
        assertEquals(0, probe.count(tableName))
        assertEquals(0, probe.search(tableName, "atualizado").size)

        probe.dropTable(tableName)
    }

    @Test
    fun androidFunctionalVerificationSuite() {
        val verification = probe.verifyFunctionalSuite("android_functional_test")
        assertTrue("Android functional verification must succeed", verification.isSuccess)
        assertTrue(verification.fts4Available)
        assertTrue(verification.unicode61Available)
        assertTrue(verification.accentFoldingWorking)
        assertTrue(verification.crudWorking)
    }

    @Test
    fun androidCorpusBenchmarkBudget() {
        val report = probe.runBenchmark(
            tableName = "android_benchmark_cues",
            corpusSize = 10_000,
            warmupRuns = 5,
            measuredRuns = 30,
            targetBudgetMillis = 1000.0,
        )

        assertTrue("FTS4 available", report.fts4Available)
        assertTrue("unicode61 available", report.unicode61Available)
        assertTrue("Accent folding working", report.accentFoldingWorking)
        assertTrue("CRUD working", report.crudWorking)
        assertTrue("p95 latency (${report.p95Millis} ms) must be within target (${report.targetBudgetMillis} ms)", report.budgetPassed)
    }
}
