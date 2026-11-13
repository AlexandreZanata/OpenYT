package com.openyt.data.search

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.openyt.core.domain.FtsCapabilityReport
import com.openyt.core.domain.FtsFunctionalVerification
import kotlin.time.TimeSource

data class ProbeCue(
    val rowId: Long = 0L,
    val entryId: String,
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    val plainText: String,
)

class SqliteFtsCapabilityProbe(
    private val sqlDriver: SqlDriver,
    private val driverName: String = "SQLDelight",
    private val osName: String = "unknown",
    private val architecture: String = "unknown",
) : TransacterImpl(sqlDriver) {

    fun probeSqliteVersion(): String = try {
        sqlDriver.executeQuery(
            identifier = null,
            sql = "SELECT sqlite_version()",
            mapper = { cursor ->
                val v = if (cursor.next().value) cursor.getString(0) ?: "unknown" else "unknown"
                QueryResult.Value(v)
            },
            parameters = 0,
        ).value
    } catch (_: Throwable) {
        "unknown"
    }

    fun createTable(tableName: String = "fts_probe", tokenizer: String = "unicode61"): Boolean = try {
        sqlDriver.execute(
            identifier = null,
            sql = "CREATE VIRTUAL TABLE IF NOT EXISTS $tableName USING fts4(entry_id, track_id, start_ms, end_ms, plain_text, tokenize=$tokenizer)",
            parameters = 0,
        ).value
        true
    } catch (_: Throwable) {
        false
    }

    fun dropTable(tableName: String = "fts_probe") {
        try {
            sqlDriver.execute(null, "DROP TABLE IF EXISTS $tableName", 0).value
        } catch (_: Throwable) {
        }
    }

    fun insertCue(tableName: String, cue: ProbeCue): Long {
        sqlDriver.execute(
            identifier = null,
            sql = "INSERT INTO $tableName (entry_id, track_id, start_ms, end_ms, plain_text) VALUES (?, ?, ?, ?, ?)",
            parameters = 5,
        ) {
            bindString(0, cue.entryId)
            bindString(1, cue.trackId)
            bindLong(2, cue.startMs)
            bindLong(3, cue.endMs)
            bindString(4, cue.plainText)
        }.value
        return sqlDriver.executeQuery(
            identifier = null,
            sql = "SELECT last_insert_rowid()",
            mapper = { cursor ->
                val rowId = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
                QueryResult.Value(rowId)
            },
            parameters = 0,
        ).value
    }

    fun insertBatch(tableName: String, cues: List<ProbeCue>) {
        transaction {
            for (cue in cues) {
                sqlDriver.execute(
                    identifier = null,
                    sql = "INSERT INTO $tableName (entry_id, track_id, start_ms, end_ms, plain_text) VALUES (?, ?, ?, ?, ?)",
                    parameters = 5,
                ) {
                    bindString(0, cue.entryId)
                    bindString(1, cue.trackId)
                    bindLong(2, cue.startMs)
                    bindLong(3, cue.endMs)
                    bindString(4, cue.plainText)
                }
            }
        }
    }

    fun search(tableName: String, matchQuery: String, limit: Int = 50): List<ProbeCue> =
        sqlDriver.executeQuery(
            identifier = null,
            sql = "SELECT rowid, entry_id, track_id, start_ms, end_ms, plain_text FROM $tableName WHERE plain_text MATCH ? LIMIT ?",
            mapper = { cursor ->
                val list = mutableListOf<ProbeCue>()
                while (cursor.next().value) {
                    list.add(
                        ProbeCue(
                            rowId = cursor.getLong(0) ?: 0L,
                            entryId = cursor.getString(1).orEmpty(),
                            trackId = cursor.getString(2).orEmpty(),
                            startMs = cursor.getLong(3) ?: 0L,
                            endMs = cursor.getLong(4) ?: 0L,
                            plainText = cursor.getString(5).orEmpty(),
                        ),
                    )
                }
                QueryResult.Value(list)
            },
            parameters = 2,
        ) {
            bindString(0, matchQuery)
            bindLong(1, limit.toLong())
        }.value

    fun updateCue(tableName: String, rowId: Long, newText: String) {
        sqlDriver.execute(
            identifier = null,
            sql = "UPDATE $tableName SET plain_text = ? WHERE rowid = ?",
            parameters = 2,
        ) {
            bindString(0, newText)
            bindLong(1, rowId)
        }.value
    }

    fun deleteCue(tableName: String, rowId: Long) {
        sqlDriver.execute(
            identifier = null,
            sql = "DELETE FROM $tableName WHERE rowid = ?",
            parameters = 1,
        ) {
            bindLong(0, rowId)
        }.value
    }

    fun count(tableName: String): Long =
        sqlDriver.executeQuery(
            identifier = null,
            sql = "SELECT count(*) FROM $tableName",
            mapper = { cursor ->
                val count = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
                QueryResult.Value(count)
            },
            parameters = 0,
        ).value

    fun verifyFunctionalSuite(tableName: String = "fts_functional_probe"): FtsFunctionalVerification {
        dropTable(tableName)
        val created = createTable(tableName, "unicode61")
        if (!created) {
            return FtsFunctionalVerification(
                fts4Available = false,
                unicode61Available = false,
                accentFoldingWorking = false,
                crudWorking = false,
                notes = "Failed to create virtual table with fts4 and unicode61",
            )
        }

        try {
            // Test 1: Portuguese accented insert and search
            val ptCue1 = ProbeCue(
                entryId = "e1",
                trackId = "t1",
                startMs = 1000,
                endMs = 4000,
                plainText = "A persistência e a dedicação são essenciais para o aprendizado da música.",
            )
            val ptCue2 = ProbeCue(
                entryId = "e1",
                trackId = "t1",
                startMs = 5000,
                endMs = 8000,
                plainText = "Não há coração sem emoção e sem paixão.",
            )
            insertCue(tableName, ptCue1)
            insertCue(tableName, ptCue2)

            // Test matching: unaccented searching accented content
            val matchMusica = search(tableName, "musica").isNotEmpty()
            val matchMusicaAccented = search(tableName, "música").isNotEmpty()
            val matchDedicacao = search(tableName, "dedicacao").isNotEmpty()
            val matchSao = search(tableName, "sao").isNotEmpty()
            val matchNao = search(tableName, "nao").isNotEmpty()
            val matchCoracao = search(tableName, "coracao").isNotEmpty()
            val matchEmocao = search(tableName, "emoção").isNotEmpty()

            // Test 2: English accented loanwords
            val enCue = ProbeCue(
                entryId = "e2",
                trackId = "t1",
                startMs = 9000,
                endMs = 12000,
                plainText = "The naïve café patron enjoyed the résumé while listening to rock 'n' roll.",
            )
            insertCue(tableName, enCue)

            val matchNaive = search(tableName, "naive").isNotEmpty()
            val matchCafe = search(tableName, "cafe").isNotEmpty()
            val matchResume = search(tableName, "resume").isNotEmpty()

            val accentFoldingSuccess = matchMusica && matchMusicaAccented && matchDedicacao &&
                matchSao && matchNao && matchCoracao && matchEmocao &&
                matchNaive && matchCafe && matchResume

            // Test 3: Incremental updates & deletion (CRUD)
            val updateCue = ProbeCue(
                entryId = "e3",
                trackId = "t1",
                startMs = 13000,
                endMs = 16000,
                plainText = "PalavraOriginalTextoParaAtualizar teste",
            )
            val rowId = insertCue(tableName, updateCue)
            val preUpdateMatch = search(tableName, "PalavraOriginalTextoParaAtualizar").isNotEmpty()

            updateCue(tableName, rowId, "PalavraModificadaTextoAtualizado teste")
            val postUpdateOldMatch = search(tableName, "PalavraOriginalTextoParaAtualizar").isEmpty()
            val postUpdateNewMatch = search(tableName, "PalavraModificadaTextoAtualizado").isNotEmpty()

            deleteCue(tableName, rowId)
            val postDeleteMatch = search(tableName, "PalavraModificadaTextoAtualizado").isEmpty()

            val crudSuccess = preUpdateMatch && postUpdateOldMatch && postUpdateNewMatch && postDeleteMatch

            return FtsFunctionalVerification(
                fts4Available = true,
                unicode61Available = true,
                accentFoldingWorking = accentFoldingSuccess,
                crudWorking = crudSuccess,
                notes = if (accentFoldingSuccess && crudSuccess) "FTS4 with unicode61 verified" else "Partial failure",
            )
        } finally {
            dropTable(tableName)
        }
    }

    fun runBenchmark(
        tableName: String = "fts_benchmark_100k",
        corpusSize: Int = 100_000,
        warmupRuns: Int = 5,
        measuredRuns: Int = 30,
        targetBudgetMillis: Double = 500.0,
    ): FtsCapabilityReport {
        dropTable(tableName)
        val created = createTable(tableName, "unicode61")
        check(created) { "Failed to create benchmark table $tableName" }

        val sqliteVer = probeSqliteVersion()
        val timeSource = TimeSource.Monotonic

        try {
            // Generate synthetic corpus
            val vocabulary = listOf(
                "música", "coração", "vídeo", "aprendizado", "sistema",
                "computador", "programação", "openyt", "arquivo", "download",
                "desenvolvimento", "execução", "persistência", "memória", "plataforma",
                "interface", "processador", "recurso", "conhecimento", "documento",
            )
            val batchSize = 5_000
            var currentId = 0
            while (currentId < corpusSize) {
                val chunkSize = minOf(batchSize, corpusSize - currentId)
                val batch = ArrayList<ProbeCue>(chunkSize)
                for (j in 0 until chunkSize) {
                    val idx = currentId + j
                    val word1 = vocabulary[idx % vocabulary.size]
                    val word2 = vocabulary[(idx / 3) % vocabulary.size]
                    val word3 = vocabulary[(idx / 7) % vocabulary.size]
                    batch.add(
                        ProbeCue(
                            entryId = "entry-${idx % 1000}",
                            trackId = "track-${idx % 5}",
                            startMs = (idx * 2000L) % 7_200_000L,
                            endMs = ((idx * 2000L) % 7_200_000L) + 1800L,
                            plainText = "Linha de legenda $idx falando sobre $word1 com $word2 e técnicas de $word3 em detalhes.",
                        ),
                    )
                }
                insertBatch(tableName, batch)
                currentId += chunkSize
            }

            // Warmup queries
            val warmupTerms = listOf("musica", "video", "download", "sistema", "aprendizado")
            for (i in 0 until warmupRuns) {
                search(tableName, warmupTerms[i % warmupTerms.size], limit = 50)
            }

            // Measured queries
            val searchQueries = listOf(
                "musica", "música", "coracao", "coração", "video", "vídeo",
                "sistema", "aprendizado", "programacao", "programação", "openyt",
                "arquivo", "download", "desenvolvimento", "execucao", "execução",
                "persistencia", "persistência", "memoria", "memória", "plataforma",
                "interface", "processador", "recurso", "conhecimento", "documento",
                "musica sistema", "video aprendizado", "openyt download", "execucao persistencia",
            )

            val latencies = ArrayList<Double>(measuredRuns)
            for (i in 0 until measuredRuns) {
                val q = searchQueries[i % searchQueries.size]
                val mark = timeSource.markNow()
                search(tableName, q, limit = 50)
                val elapsed = mark.elapsedNow().inWholeNanoseconds / 1_000_000.0
                latencies.add(elapsed)
            }

            latencies.sort()
            val minLat = latencies.first()
            val maxLat = latencies.last()
            val avgLat = latencies.sum() / latencies.size
            val p50 = latencies[latencies.size / 2]
            val p90 = latencies[(latencies.size * 0.90).toInt().coerceAtMost(latencies.size - 1)]
            val p95 = latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)]
            val budgetPassed = p95 <= targetBudgetMillis

            // Functional sanity check
            val functional = verifyFunctionalSuite(tableName + "_func")

            return FtsCapabilityReport(
                sqliteVersion = sqliteVer,
                driverName = driverName,
                osName = osName,
                architecture = architecture,
                fts4Available = true,
                unicode61Available = true,
                accentFoldingWorking = functional.accentFoldingWorking,
                crudWorking = functional.crudWorking,
                corpusSize = corpusSize,
                warmupRuns = warmupRuns,
                measuredRuns = measuredRuns,
                p50Millis = p50,
                p90Millis = p90,
                p95Millis = p95,
                maxMillis = maxLat,
                averageMillis = avgLat,
                budgetPassed = budgetPassed,
                targetBudgetMillis = targetBudgetMillis,
                timestampEpochMillis = timeSource.markNow().elapsedNow().inWholeMilliseconds + 1700000000000L,
            )
        } finally {
            dropTable(tableName)
        }
    }
}
