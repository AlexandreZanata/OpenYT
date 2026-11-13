package com.openyt.data.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteFtsCapabilityProbeTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var probe: SqliteFtsCapabilityProbe

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        probe = SqliteFtsCapabilityProbe(
            sqlDriver = driver,
            driverName = "JdbcSqliteDriver (Xerial SQLite JDBC)",
            osName = System.getProperty("os.name").orEmpty(),
            architecture = System.getProperty("os.arch").orEmpty(),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun tableCreationFts4WithUnicode61() {
        val sqliteVer = probe.probeSqliteVersion()
        assertTrue(sqliteVer.isNotEmpty(), "SQLite version should not be empty")
        println("Probe SQLite version: $sqliteVer")

        val created = probe.createTable("test_creation", "unicode61")
        assertTrue(created, "FTS4 table with unicode61 tokenizer should be created successfully")

        probe.dropTable("test_creation")
    }

    @Test
    fun parameterBindingAndInjectionSafety() {
        val tableName = "test_binding_safety"
        probe.createTable(tableName, "unicode61")

        val maliciousTexts = listOf(
            "' OR '1'='1",
            "Robert'); DROP TABLE students;--",
            "Test with \"double quotes\" and 'single quotes'",
            "Code snippet: if (x < 10 && y > 20) { return true; }",
            "Special characters: !@#\$%^&*()_+-=[]{}|;':,./<>?",
            "Emoji text: 🎥 Vídeo incrível com legendas! 🚀",
        )

        maliciousTexts.forEachIndexed { index, text ->
            val cue = ProbeCue(
                entryId = "injection-entry-$index",
                trackId = "track-$index",
                startMs = index * 1000L,
                endMs = (index + 1) * 1000L,
                plainText = text,
            )
            val rowId = probe.insertCue(tableName, cue)
            assertTrue(rowId > 0, "Insert should succeed and return positive rowid for cue $index")
        }

        assertEquals(maliciousTexts.size.toLong(), probe.count(tableName))

        val results = probe.search(tableName, "Robert", limit = 10)
        assertEquals(1, results.size)
        assertTrue(results.first().plainText.contains("DROP TABLE"))

        probe.dropTable(tableName)
    }

    @Test
    fun unicodeTokenizationAndAccentFolding() {
        val tableName = "test_accent_folding"
        probe.createTable(tableName, "unicode61")

        val cues = listOf(
            ProbeCue(entryId = "e1", trackId = "t1", startMs = 0, endMs = 2000, plainText = "A persistência e a dedicação são fundamentais."),
            ProbeCue(entryId = "e1", trackId = "t1", startMs = 2000, endMs = 4000, plainText = "Estudando a música popular brasileira com emoção."),
            ProbeCue(entryId = "e1", trackId = "t1", startMs = 4000, endMs = 6000, plainText = "Não há dúvida sobre a eficiência do algoritmo."),
            ProbeCue(entryId = "e1", trackId = "t1", startMs = 6000, endMs = 8000, plainText = "Açúcar e café com o novo resumo técnico."),
            ProbeCue(entryId = "e1", trackId = "t1", startMs = 8000, endMs = 10000, plainText = "The naïve café visitor read the résumé."),
        )
        probe.insertBatch(tableName, cues)

        // Portuguese accent folding: search unaccented query against accented text
        assertEquals(1, probe.search(tableName, "persistencia").size, "persistencia should match persistência")
        assertEquals(1, probe.search(tableName, "persistência").size, "persistência should match persistência")
        assertEquals(1, probe.search(tableName, "dedicacao").size, "dedicacao should match dedicação")
        assertEquals(1, probe.search(tableName, "dedicação").size, "dedicação should match dedicação")
        assertEquals(1, probe.search(tableName, "musica").size, "musica should match música")
        assertEquals(1, probe.search(tableName, "música").size, "música should match música")
        assertEquals(1, probe.search(tableName, "emocao").size, "emocao should match emoção")
        assertEquals(1, probe.search(tableName, "nao").size, "nao should match Não")
        assertEquals(1, probe.search(tableName, "não").size, "não should match Não")
        assertEquals(1, probe.search(tableName, "duvida").size, "duvida should match dúvida")
        assertEquals(1, probe.search(tableName, "acucar").size, "acucar should match Açúcar")
        assertEquals(1, probe.search(tableName, "açúcar").size, "açúcar should match Açúcar")

        // English loanwords / accents:
        assertEquals(2, probe.search(tableName, "cafe").size, "cafe should match both café occurrences")
        assertEquals(1, probe.search(tableName, "naive").size, "naive should match naïve")
        assertEquals(1, probe.search(tableName, "naïve").size, "naïve should match naïve")
        assertEquals(1, probe.search(tableName, "resume").size, "resume should match résumé")

        // Case insensitivity
        assertEquals(1, probe.search(tableName, "BRASILEIRA").size)
        assertEquals(1, probe.search(tableName, "brasileira").size)

        probe.dropTable(tableName)
    }

    @Test
    fun incrementalUpdatesAndDeletionCrud() {
        val tableName = "test_crud"
        probe.createTable(tableName, "unicode61")

        val cue = ProbeCue(
            entryId = "crud-entry",
            trackId = "track-1",
            startMs = 1000,
            endMs = 3000,
            plainText = "Texto original em português para teste",
        )
        val rowId = probe.insertCue(tableName, cue)
        assertEquals(1, probe.count(tableName))
        assertEquals(1, probe.search(tableName, "original").size)

        // Update
        probe.updateCue(tableName, rowId, "Texto modificado e atualizado com novidades")
        assertEquals(0, probe.search(tableName, "original").size, "Old text should no longer match")
        assertEquals(1, probe.search(tableName, "modificado").size, "New text should match")
        assertEquals(1, probe.search(tableName, "novidades").size, "New text should match")

        // Delete
        probe.deleteCue(tableName, rowId)
        assertEquals(0, probe.count(tableName))
        assertEquals(0, probe.search(tableName, "modificado").size, "Deleted cue should not match")

        probe.dropTable(tableName)
    }

    @Test
    fun unsupportedTokenizerRejection() {
        val tableName = "test_bad_tokenizer"
        val created = probe.createTable(tableName, "invalid_tokenizer_never_exists_12345")
        assertFalse(created, "Invalid tokenizer should fail gracefully and return false")
    }

    @Test
    fun functionalSuiteVerification() {
        val result = probe.verifyFunctionalSuite("test_functional_suite")
        assertTrue(result.isSuccess, "Functional verification suite must pass: ${result.notes}")
        assertTrue(result.fts4Available)
        assertTrue(result.unicode61Available)
        assertTrue(result.accentFoldingWorking)
        assertTrue(result.crudWorking)
    }

    @Test
    fun measuredCorpus100kBenchmark() {
        val report = probe.runBenchmark(
            tableName = "benchmark_cues_100k",
            corpusSize = 100_000,
            warmupRuns = 5,
            measuredRuns = 30,
            targetBudgetMillis = 500.0,
        )

        println("=== NX-S01 100k Corpus Benchmark Report ===")
        println("SQLite Version: ${report.sqliteVersion}")
        println("Driver: ${report.driverName}")
        println("Platform: ${report.osName} (${report.architecture})")
        println("Corpus size: ${report.corpusSize} cues")
        println("Warmup runs: ${report.warmupRuns}")
        println("Measured runs: ${report.measuredRuns}")
        println("Latencies (ms): min=%.3f, p50=%.3f, p90=%.3f, p95=%.3f, max=%.3f, avg=%.3f".format(
            report.maxMillis, report.p50Millis, report.p90Millis, report.p95Millis, report.maxMillis, report.averageMillis
        ))
        println("Budget target: <= ${report.targetBudgetMillis} ms")
        println("Budget passed: ${report.budgetPassed}")
        println("Accent folding: ${report.accentFoldingWorking}")
        println("CRUD working: ${report.crudWorking}")
        println("==========================================")

        assertTrue(report.fts4Available, "FTS4 should be available")
        assertTrue(report.unicode61Available, "unicode61 tokenizer should be available")
        assertTrue(report.accentFoldingWorking, "Accent folding should work")
        assertTrue(report.crudWorking, "CRUD operations should work")
        assertEquals(100_000, report.corpusSize)
        assertEquals(5, report.warmupRuns)
        assertEquals(30, report.measuredRuns)
        assertTrue(report.budgetPassed, "p95 latency (${report.p95Millis} ms) must be within target budget (${report.targetBudgetMillis} ms)")
    }
}
