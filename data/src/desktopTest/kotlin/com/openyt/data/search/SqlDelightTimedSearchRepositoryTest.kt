package com.openyt.data.search

import com.openyt.core.domain.IndexUnavailableReason
import com.openyt.core.domain.SubtitleIndexState
import com.openyt.core.domain.TimedCue
import com.openyt.core.domain.TimedSearchFilter
import com.openyt.core.domain.TimedSearchIndexStatus
import com.openyt.core.domain.TimedSearchPagination
import com.openyt.core.domain.TimedSearchResult
import com.openyt.core.domain.TimedSearchScope
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightTimedSearchRepositoryTest {

    private fun insertEntry(
        database: OpenYtDatabase,
        entryId: String,
        title: String = "Title $entryId",
    ) {
        database.downloadQueries.insertLibraryEntry(
            id = entryId,
            title = title,
            author = "Author",
            kind = "VIDEO",
            file_path = "/path/$entryId.mp4",
            size_bytes = 1000L,
            thumbnail_url = null,
            source = "local",
            media_revision_size = 1000L,
            media_revision_mtime = 1000L,
            media_revision_sha256 = "sha-$entryId",
            last_verified_at = 1000L,
        )
    }

    private suspend fun insertReadyTrack(
        database: OpenYtDatabase,
        indexRepo: SqlDelightSubtitleIndexRepository,
        trackId: String,
        entryId: String,
        language: String = "pt",
        cues: List<TimedCue>,
    ) {
        val rev = indexRepo.beginRevision(
            trackId = trackId,
            entryId = entryId,
            language = language,
            mediaRevisionToken = "media-rev-1",
            trackRevision = "track-rev-1",
        )
        indexRepo.persistCues(cues, trackId, entryId, rev)
        indexRepo.commitRevision(trackId, rev, cues.size, timebaseOffsetMs = 0L)
    }

    @Test
    fun scopedSearchSupportsLibraryCollectionAndEntryScopes() = runBlocking {
        val file = File.createTempFile("openyt-search-scopes-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)

            insertEntry(database, "entry-1", "Video 1")
            insertEntry(database, "entry-2", "Video 2")

            database.downloadQueries.insertCollection("col-1", "Collection 1")
            database.downloadQueries.insertCollectionEntry("col-1", "entry-1")

            val cues1 = listOf(
                TimedCue(
                    cueId = "c1-1",
                    trackId = "track-1",
                    entryId = "entry-1",
                    startMs = 1000L,
                    endMs = 3000L,
                    displayText = "Aprendizado de programação em Kotlin",
                    plainText = "Aprendizado de programação em Kotlin",
                    originalIndex = 1,
                ),
            )
            val cues2 = listOf(
                TimedCue(
                    cueId = "c2-1",
                    trackId = "track-2",
                    entryId = "entry-2",
                    startMs = 2000L,
                    endMs = 4000L,
                    displayText = "Avançado aprendizado em arquitetura limpa",
                    plainText = "Avançado aprendizado em arquitetura limpa",
                    originalIndex = 1,
                ),
            )

            insertReadyTrack(database, indexRepo, "track-1", "entry-1", "pt", cues1)
            insertReadyTrack(database, indexRepo, "track-2", "entry-2", "pt", cues2)

            // 1. Library scope: matches both entries
            val libResult = searchRepo.search("aprendizado", scope = TimedSearchScope.Library)
            assertIs<TimedSearchResult.Success>(libResult)
            assertEquals(2, libResult.totalMatches)
            assertEquals(2, libResult.items.size)

            // 2. Collection scope: matches only entry-1
            val colResult = searchRepo.search("aprendizado", scope = TimedSearchScope.Collection("col-1"))
            assertIs<TimedSearchResult.Success>(colResult)
            assertEquals(1, colResult.totalMatches)
            assertEquals("entry-1", colResult.items.single().entryId)

            // 3. Entry scope: matches only entry-2
            val entryResult = searchRepo.search("aprendizado", scope = TimedSearchScope.Entry("entry-2"))
            assertIs<TimedSearchResult.Success>(entryResult)
            assertEquals(1, entryResult.totalMatches)
            assertEquals("entry-2", entryResult.items.single().entryId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun filtersByExplicitLanguageAndTrackId() = runBlocking {
        val file = File.createTempFile("openyt-search-filters-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)

            insertEntry(database, "entry-1", "Video 1")

            val cuesPt = listOf(
                TimedCue(
                    cueId = "cue-pt",
                    trackId = "track-pt",
                    entryId = "entry-1",
                    startMs = 1000L,
                    endMs = 2000L,
                    displayText = "Olá mundo com café",
                    plainText = "Olá mundo com café",
                    originalIndex = 1,
                ),
            )
            val cuesEn = listOf(
                TimedCue(
                    cueId = "cue-en",
                    trackId = "track-en",
                    entryId = "entry-1",
                    startMs = 3000L,
                    endMs = 4000L,
                    displayText = "Hello world with coffee",
                    plainText = "Hello world with coffee",
                    originalIndex = 1,
                ),
            )

            insertReadyTrack(database, indexRepo, "track-pt", "entry-1", "pt", cuesPt)
            insertReadyTrack(database, indexRepo, "track-en", "entry-1", "en", cuesEn)

            // Filter language = pt
            val ptResult = searchRepo.search(
                query = "mundo",
                scope = TimedSearchScope.Library,
                filter = TimedSearchFilter(language = "pt"),
            )
            assertIs<TimedSearchResult.Success>(ptResult)
            assertEquals(1, ptResult.totalMatches)
            assertEquals("track-pt", ptResult.items.single().trackId)

            // Filter trackId = track-en
            val enResult = searchRepo.search(
                query = "coffee",
                scope = TimedSearchScope.Library,
                filter = TimedSearchFilter(trackId = "track-en"),
            )
            assertIs<TimedSearchResult.Success>(enResult)
            assertEquals(1, enResult.totalMatches)
            assertEquals("track-en", enResult.items.single().trackId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun paginationIsDeterministicAndHandlesTies() = runBlocking {
        val file = File.createTempFile("openyt-search-paging-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)

            insertEntry(database, "entry-1", "Video 1")

            // 5 cues with identical startMs = 5000L to test tie-breaking on cueId
            val cues = (1..5).map { idx ->
                TimedCue(
                    cueId = "cue-$idx",
                    trackId = "track-1",
                    entryId = "entry-1",
                    startMs = 5000L,
                    endMs = 6000L,
                    displayText = "Linha repetida com termo fixo índice $idx",
                    plainText = "Linha repetida com termo fixo índice $idx",
                    originalIndex = idx,
                )
            }
            insertReadyTrack(database, indexRepo, "track-1", "entry-1", "pt", cues)

            val page0 = searchRepo.search(
                query = "termo fixo",
                scope = TimedSearchScope.Entry("entry-1"),
                pagination = TimedSearchPagination(page = 0, pageSize = 2),
            )
            assertIs<TimedSearchResult.Success>(page0)
            assertEquals(5, page0.totalMatches)
            assertEquals(2, page0.items.size)
            assertTrue(page0.hasMore)
            assertEquals("cue-1", page0.items[0].cueId)
            assertEquals("cue-2", page0.items[1].cueId)

            val page1 = searchRepo.search(
                query = "termo fixo",
                scope = TimedSearchScope.Entry("entry-1"),
                pagination = TimedSearchPagination(page = 1, pageSize = 2),
            )
            assertIs<TimedSearchResult.Success>(page1)
            assertEquals(5, page1.totalMatches)
            assertEquals(2, page1.items.size)
            assertTrue(page1.hasMore)
            assertEquals("cue-3", page1.items[0].cueId)
            assertEquals("cue-4", page1.items[1].cueId)

            val page2 = searchRepo.search(
                query = "termo fixo",
                scope = TimedSearchScope.Entry("entry-1"),
                pagination = TimedSearchPagination(page = 2, pageSize = 2),
            )
            assertIs<TimedSearchResult.Success>(page2)
            assertEquals(5, page2.totalMatches)
            assertEquals(1, page2.items.size)
            assertFalse(page2.hasMore)
            assertEquals("cue-5", page2.items[0].cueId)

            // Confirm all 5 items collected with zero duplicates and zero gaps
            val allIds = page0.items.map { it.cueId } + page1.items.map { it.cueId } + page2.items.map { it.cueId }
            assertEquals(listOf("cue-1", "cue-2", "cue-3", "cue-4", "cue-5"), allIds)
        } finally {
            file.delete()
        }
    }

    @Test
    fun deletedEntryImmediatelyExcludesCuesFromSearch() = runBlocking {
        val file = File.createTempFile("openyt-search-deleted-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)

            insertEntry(database, "entry-to-delete", "Temporary Video")

            val cues = listOf(
                TimedCue(
                    cueId = "cue-del-1",
                    trackId = "track-del",
                    entryId = "entry-to-delete",
                    startMs = 1000L,
                    endMs = 2000L,
                    displayText = "Conteúdo exclusivo prestes a sumir",
                    plainText = "Conteúdo exclusivo prestes a sumir",
                    originalIndex = 1,
                ),
            )
            insertReadyTrack(database, indexRepo, "track-del", "entry-to-delete", "pt", cues)

            // Initial search finds it
            val initial = searchRepo.search("exclusivo", scope = TimedSearchScope.Library)
            assertIs<TimedSearchResult.Success>(initial)
            assertEquals(1, initial.totalMatches)

            // Delete entry from library_entry table
            database.downloadQueries.deleteLibraryEntry("entry-to-delete")

            // Searching library immediately returns zero matches (even if cues exist in db)
            val afterDeleteLibrary = searchRepo.search("exclusivo", scope = TimedSearchScope.Library)
            assertIs<TimedSearchResult.IndexUnavailable>(afterDeleteLibrary)
            assertEquals(IndexUnavailableReason.NO_SUBTITLE_TRACKS, afterDeleteLibrary.reason)

            // Searching deleted entry returns ENTRY_NOT_FOUND
            val afterDeleteEntry = searchRepo.search("exclusivo", scope = TimedSearchScope.Entry("entry-to-delete"))
            assertIs<TimedSearchResult.IndexUnavailable>(afterDeleteEntry)
            assertEquals(IndexUnavailableReason.ENTRY_NOT_FOUND, afterDeleteEntry.reason)
        } finally {
            file.delete()
        }
    }

    @Test
    fun literalTreatmentOfQuotesOperatorsAndSqlSyntax() = runBlocking {
        val file = File.createTempFile("openyt-search-literal-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)

            insertEntry(database, "entry-literal", "Literal Test Video")

            val cues = listOf(
                TimedCue(
                    cueId = "c-ops",
                    trackId = "track-lit",
                    entryId = "entry-literal",
                    startMs = 1000L,
                    endMs = 2000L,
                    displayText = "Texto contendo operadores AND OR NOT NEAR/3 literais",
                    plainText = "Texto contendo operadores AND OR NOT NEAR/3 literais",
                    originalIndex = 1,
                ),
                TimedCue(
                    cueId = "c-quotes",
                    trackId = "track-lit",
                    entryId = "entry-literal",
                    startMs = 3000L,
                    endMs = 4000L,
                    displayText = "Texto com \"aspas duplas\" e 'aspas simples'",
                    plainText = "Texto com \"aspas duplas\" e 'aspas simples'",
                    originalIndex = 2,
                ),
                TimedCue(
                    cueId = "c-sql",
                    trackId = "track-lit",
                    entryId = "entry-literal",
                    startMs = 5000L,
                    endMs = 6000L,
                    displayText = "Tentativa de injection '; DROP TABLE subtitle_cue; --",
                    plainText = "Tentativa de injection '; DROP TABLE subtitle_cue; --",
                    originalIndex = 3,
                ),
                TimedCue(
                    cueId = "c-wildcard",
                    trackId = "track-lit",
                    entryId = "entry-literal",
                    startMs = 7000L,
                    endMs = 8000L,
                    displayText = "Linha com asterisco *wildcard* isolado",
                    plainText = "Linha com asterisco *wildcard* isolado",
                    originalIndex = 4,
                ),
            )
            insertReadyTrack(database, indexRepo, "track-lit", "entry-literal", "pt", cues)

            // Search literal "AND OR"
            val opsResult = searchRepo.search("AND OR", scope = TimedSearchScope.Entry("entry-literal"))
            assertIs<TimedSearchResult.Success>(opsResult)
            assertEquals(1, opsResult.totalMatches)
            assertEquals("c-ops", opsResult.items.single().cueId)

            // Search literal quotes
            val quotesResult = searchRepo.search("\"aspas duplas\"", scope = TimedSearchScope.Entry("entry-literal"))
            assertIs<TimedSearchResult.Success>(quotesResult)
            assertEquals(1, quotesResult.totalMatches)
            assertEquals("c-quotes", quotesResult.items.single().cueId)

            // Search SQL injection syntax
            val sqlResult = searchRepo.search("'; DROP TABLE", scope = TimedSearchScope.Entry("entry-literal"))
            assertIs<TimedSearchResult.Success>(sqlResult)
            assertEquals(1, sqlResult.totalMatches)
            assertEquals("c-sql", sqlResult.items.single().cueId)

            // Confirm table still exists and is not dropped
            val checkCount = database.downloadQueries.countSubtitleCuesEntry(
                match_query = "\"injection\"",
                entry_id = "entry-literal",
                language = null,
                track_id = null,
            ).executeAsOne()
            assertEquals(1L, checkCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun cjkAndUnspacedTextSearchBehavior() = runBlocking {
        val file = File.createTempFile("openyt-search-cjk-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)

            insertEntry(database, "entry-cjk", "CJK Video")

            val cues = listOf(
                TimedCue(
                    cueId = "c-cjk",
                    trackId = "track-cjk",
                    entryId = "entry-cjk",
                    startMs = 1000L,
                    endMs = 3000L,
                    displayText = "这是一个测试 世界和平",
                    plainText = "这是一个测试 世界和平",
                    originalIndex = 1,
                ),
            )
            insertReadyTrack(database, indexRepo, "track-cjk", "entry-cjk", "zh", cues)

            // Exact token search succeeds
            val res1 = searchRepo.search("这是一个测试", scope = TimedSearchScope.Entry("entry-cjk"))
            assertIs<TimedSearchResult.Success>(res1)
            assertEquals(1, res1.totalMatches)
            assertEquals("这是一个测试", res1.items.single().excerpt.text.substring(0, 6))

            val res2 = searchRepo.search("世界和平", scope = TimedSearchScope.Entry("entry-cjk"))
            assertIs<TimedSearchResult.Success>(res2)
            assertEquals(1, res2.totalMatches)
        } finally {
            file.delete()
        }
    }

    @Test
    fun distinctTypedStatesForUnavailableIndexVsZeroMatches() = runBlocking {
        val file = File.createTempFile("openyt-search-states-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)

            insertEntry(database, "entry-no-tracks", "No Tracks Video")
            insertEntry(database, "entry-indexing", "Indexing Video")
            insertEntry(database, "entry-failed", "Failed Video")
            insertEntry(database, "entry-ready", "Ready Video")

            // 1. No tracks
            val noTracks = searchRepo.search("query", scope = TimedSearchScope.Entry("entry-no-tracks"))
            assertIs<TimedSearchResult.IndexUnavailable>(noTracks)
            assertEquals(IndexUnavailableReason.NO_SUBTITLE_TRACKS, noTracks.reason)

            // 2. Indexing track (not ready yet)
            indexRepo.beginRevision("track-indexing", "entry-indexing", "pt", "m1", "t1")
            val indexing = searchRepo.search("query", scope = TimedSearchScope.Entry("entry-indexing"))
            assertIs<TimedSearchResult.IndexUnavailable>(indexing)
            assertEquals(IndexUnavailableReason.INDEX_PENDING_OR_STALE, indexing.reason)

            // 3. Failed track
            indexRepo.saveTrackIndex(
                com.openyt.core.domain.SubtitleTrackIndexRecord(
                    trackId = "track-failed",
                    entryId = "entry-failed",
                    language = "pt",
                    mediaRevisionToken = "m1",
                    trackRevision = "t1",
                    activeRevision = 0,
                    state = SubtitleIndexState.FAILED,
                    totalCues = 0,
                    errorMessage = "Syntax error in SRT",
                ),
            )
            val failed = searchRepo.search("query", scope = TimedSearchScope.Entry("entry-failed"))
            assertIs<TimedSearchResult.IndexUnavailable>(failed)
            assertEquals(IndexUnavailableReason.INDEX_FAILED, failed.reason)

            // 4. Ready track, but query matches zero cues -> Success with emptyList()
            val cues = listOf(
                TimedCue(
                    cueId = "c1",
                    trackId = "track-ready",
                    entryId = "entry-ready",
                    startMs = 1000L,
                    endMs = 2000L,
                    displayText = "Legenda disponível aqui",
                    plainText = "Legenda disponível aqui",
                    originalIndex = 1,
                ),
            )
            insertReadyTrack(database, indexRepo, "track-ready", "entry-ready", "pt", cues)

            val zeroMatches = searchRepo.search("inexistente", scope = TimedSearchScope.Entry("entry-ready"))
            assertIs<TimedSearchResult.Success>(zeroMatches)
            assertEquals(0, zeroMatches.totalMatches)
            assertTrue(zeroMatches.items.isEmpty())
            assertEquals(TimedSearchIndexStatus.READY, zeroMatches.indexStatus)
        } finally {
            file.delete()
        }
    }

    @Test
    fun coroutineCancellationTerminatesCooperatively() = runBlocking {
        val file = File.createTempFile("openyt-search-cancel-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val searchRepo = SqlDelightTimedSearchRepository(database)

            insertEntry(database, "entry-c", "Cancel Video")
            val cues = (1..100).map {
                TimedCue(
                    cueId = "c-$it",
                    trackId = "track-c",
                    entryId = "entry-c",
                    startMs = it * 1000L,
                    endMs = (it * 1000L) + 800L,
                    displayText = "Linha cancelável $it",
                    plainText = "Linha cancelável $it",
                    originalIndex = it,
                )
            }
            insertReadyTrack(database, indexRepo, "track-c", "entry-c", "pt", cues)

            val job = async {
                searchRepo.search("cancelavel", scope = TimedSearchScope.Entry("entry-c"))
            }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            file.delete()
        }
    }

    @Test
    fun measuredSyntheticCorpusBudgetUnder500ms() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        val database = OpenYtDatabase(driver)
        val indexRepo = SqlDelightSubtitleIndexRepository(database)
        val searchRepo = SqlDelightTimedSearchRepository(database)
        try {
            insertEntry(database, "entry-perf", "Performance Video")

        // Insert 100,000 synthetic cues
        val totalCues = 100_000
        val batchSize = 10_000
        val rev = indexRepo.beginRevision(
            trackId = "track-perf",
            entryId = "entry-perf",
            language = "pt",
            mediaRevisionToken = "perf-media",
            trackRevision = "perf-track",
        )

            val phrases = listOf(
                "café com açúcar e música clássica brasileira",
                "desenvolvimento de software moderno em kotlin multiplatform",
                "pesquisa rápida e eficiente em banco de dados sqlite com fts",
                "busca textual delimitada em biblioteca offline com paginação",
            )

            var globalIdx = 0
            for (batchIdx in 0 until (totalCues / batchSize)) {
                val batch = ArrayList<TimedCue>(batchSize)
                for (i in 0 until batchSize) {
                    val phrase = phrases[globalIdx % phrases.size]
                    batch.add(
                        TimedCue(
                            cueId = "cue-$globalIdx",
                            trackId = "track-perf",
                            entryId = "entry-perf",
                            startMs = globalIdx * 2000L,
                            endMs = (globalIdx * 2000L) + 1500L,
                            displayText = "$phrase $globalIdx",
                            plainText = "$phrase $globalIdx",
                            originalIndex = globalIdx + 1,
                        ),
                    )
                    globalIdx++
                }
                indexRepo.persistCues(batch, "track-perf", "entry-perf", rev)
            }
            indexRepo.commitRevision("track-perf", rev, totalCues, timebaseOffsetMs = 0L)

            // Warm up
            searchRepo.search("cafe musica", scope = TimedSearchScope.Entry("entry-perf"))
            searchRepo.search("sqlite fts", scope = TimedSearchScope.Entry("entry-perf"))

            // Benchmark 50 query executions
            val iterations = 50
            val latenciesMs = DoubleArray(iterations)
            for (i in 0 until iterations) {
                val term = if (i % 2 == 0) "cafe musica" else "sqlite fts"
                val elapsedNanos = measureNanoTime {
                    val result = searchRepo.search(term, scope = TimedSearchScope.Entry("entry-perf"))
                    assertIs<TimedSearchResult.Success>(result)
                    assertTrue(result.totalMatches > 0)
                }
                latenciesMs[i] = elapsedNanos / 1_000_000.0
            }

            latenciesMs.sort()
            val p95Index = ((iterations * 0.95).toInt()).coerceAtMost(iterations - 1)
            val p95Latency = latenciesMs[p95Index]

            println("100k-cue SQLite TimedSearchRepository p95 latency: ${p95Latency}ms (budget <= 500ms)")
            assertTrue(
                p95Latency <= 500.0,
                "p95 latency ($p95Latency ms) exceeded budget of 500 ms on 100k-cue synthetic corpus",
            )
        } finally {
            driver.close()
        }
    }
}
