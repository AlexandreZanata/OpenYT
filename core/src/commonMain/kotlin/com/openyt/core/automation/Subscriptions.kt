package com.openyt.core.automation

import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.UrlValidation
import com.openyt.core.application.UrlValidator
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
enum class SubscriptionKind { PLAYLIST, FEED }

@Serializable
data class SubscriptionRule(
    val id: String,
    val name: String,
    val url: String,
    val kind: SubscriptionKind,
    val intervalMinutes: Int = 60,
    val enabled: Boolean = true,
    val includeExisting: Boolean = false,
    val initialized: Boolean = false,
    val seenIds: Set<String> = emptySet(),
    val lastCheckMillis: Long = 0,
    val lastAttemptMillis: Long = 0,
)

data class FeedEpisode(val id: String, val title: String, val url: String)
fun interface FeedReader { suspend fun episodes(url: String): List<FeedEpisode> }
interface SubscriptionRepository {
    suspend fun load(): List<SubscriptionRule>
    suspend fun save(rule: SubscriptionRule)
    suspend fun delete(id: String)
}

/** A foreground scheduler. Its persisted seen-set makes repeated checks idempotent. */
class SubscriptionController(
    private val repository: SubscriptionRepository,
    private val feeds: FeedReader,
    private val engine: DownloadEngine,
    private val coordinator: DownloadCoordinator,
    private val directory: () -> String,
    private val nowMillis: () -> Long,
    private val nextId: () -> String,
    private val scope: CoroutineScope,
) {
    private val current = MutableStateFlow<List<SubscriptionRule>>(emptyList())
    val rules = current.asStateFlow()
    private val failures = MutableStateFlow<Set<String>>(emptySet())
    val failedIds = failures.asStateFlow()
    private val checking = MutableStateFlow<Set<String>>(emptySet())
    val checkingIds = checking.asStateFlow()
    private val mutation = Mutex()
    private var scheduler: Job? = null

    suspend fun restore() { current.value = repository.load() }

    fun start() {
        if (scheduler?.isActive == true) return
        scheduler = scope.launch {
            while (isActive) {
                checkDue()
                delay(30_000)
            }
        }
    }

    fun stop() { scheduler?.cancel(); scheduler = null }

    suspend fun add(name: String, url: String, kind: SubscriptionKind, intervalMinutes: Int, includeExisting: Boolean): Boolean = mutation.withLock {
        val validated = UrlValidator().validate(url) as? UrlValidation.Valid ?: return@withLock false
        if (name.isBlank() || intervalMinutes !in 1..10_080) return@withLock false
        if (current.value.any { it.url == validated.url && it.kind == kind }) return@withLock false
        val rule = SubscriptionRule(nextId(), name.trim().take(120), validated.url, kind, intervalMinutes, includeExisting = includeExisting)
        repository.save(rule)
        current.update { it + rule }
        true
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutation.withLock {
        val rule = current.value.firstOrNull { it.id == id } ?: return@withLock
        val updated = rule.copy(enabled = enabled)
        repository.save(updated)
        current.update { items -> items.map { if (it.id == id) updated else it } }
    }

    suspend fun remove(id: String) = mutation.withLock {
        repository.delete(id)
        current.update { rules -> rules.filterNot { it.id == id } }
        failures.update { it - id }
    }

    suspend fun checkDue() {
        val now = nowMillis()
        current.value.filter { it.enabled && (maxOf(it.lastCheckMillis, it.lastAttemptMillis) == 0L || now - maxOf(it.lastCheckMillis, it.lastAttemptMillis) >= it.intervalMinutes * 60_000L) }
            .forEach { check(it.id) }
    }

    suspend fun check(id: String): Int {
        if (id in checking.value) return 0
        checking.update { it + id }
        try {
            return mutation.withLock {
                val rule = current.value.firstOrNull { it.id == id } ?: return@withLock 0
                val attempted = rule.copy(lastAttemptMillis = nowMillis())
                repository.save(attempted)
                current.update { rules -> rules.map { if (it.id == id) attempted else it } }
                val episodes = if (rule.kind == SubscriptionKind.FEED) {
                    feeds.episodes(rule.url)
                } else {
                    when (val inspection = engine.inspect(rule.url)) {
                        is EngineResult.Failure -> error("playlist inspection failed")
                        is EngineResult.Success -> when (val value = inspection.value) {
                            is Inspection.Playlist -> value.playlist.entries.map { FeedEpisode(it.url, it.title, it.url) }
                            is Inspection.Single -> listOf(FeedEpisode(value.media.sourceId, value.media.title, value.media.webpageUrl))
                        }
                    }
                }.distinctBy { it.id }
                val unseen = episodes.filter { it.id !in rule.seenIds }
                val selected = if (rule.initialized || rule.includeExisting) unseen else emptyList()
                var queued = 0
                selected.forEach { episode ->
                    val validated = UrlValidator().validate(episode.url) as? UrlValidation.Valid ?: return@forEach
                    val kind = if (rule.kind == SubscriptionKind.FEED) MediaKind.AUDIO else MediaKind.VIDEO
                    coordinator.enqueue(
                        MediaInfo(episode.id, if (rule.kind == SubscriptionKind.FEED) "podcast" else "subscription", episode.title, webpageUrl = validated.url),
                        DownloadRequest(validated.url, kind, destinationDirectory = directory(), options = DownloadOptions(originKey = "${rule.id}:${episode.id}")),
                    )
                    queued++
                }
                kotlin.check(coordinator.awaitPersistence()) { "queue persistence unavailable" }
                val updated = attempted.copy(initialized = true, seenIds = rule.seenIds + episodes.map { it.id }, lastCheckMillis = nowMillis())
                repository.save(updated)
                current.update { rules -> rules.map { if (it.id == id) updated else it } }
                failures.update { it - id }
                queued
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.update { it + id }
            return 0
        } finally { checking.update { it - id } }
    }
}
