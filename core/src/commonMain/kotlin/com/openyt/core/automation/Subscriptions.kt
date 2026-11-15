package com.openyt.core.automation

import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.application.DownloadProfileRepository
import com.openyt.core.application.UrlValidation
import com.openyt.core.application.UrlValidator
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadProfileResolver
import com.openyt.core.domain.ProfileResolution
import com.openyt.core.domain.toDownloadRequest
import com.openyt.core.domain.DownloadExecutionConstraints
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
    val revision: Int = 1,
    val filters: SubscriptionFilters = SubscriptionFilters(),
    val candidates: List<SubscriptionCandidate> = emptyList(),
    val discoveryLimited: Boolean = false,
    val nextCandidateId: String? = null,
    val archivedFilteredIds: Set<String> = emptySet(),
    val lastFailure: SubscriptionReason? = null,
)

@Serializable
data class FeedEpisode(val id: String, val title: String, val url: String, val durationSeconds: Long? = null, val sizeBytes: Long? = null)
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
    private val profileRepository: DownloadProfileRepository? = null,
    private val environment: SubscriptionEnvironment = SubscriptionEnvironment { SubscriptionDeviceState(connected = true) },
    private val exportTargetUri: () -> String? = { null },
    val backgroundScheduler: SubscriptionBackgroundScheduler? = null,
) {
    private val current = MutableStateFlow<List<SubscriptionRule>>(emptyList())
    val rules = current.asStateFlow()
    private val failures = MutableStateFlow<Set<String>>(emptySet())
    val failedIds = failures.asStateFlow()
    private val checking = MutableStateFlow<Set<String>>(emptySet())
    val checkingIds = checking.asStateFlow()
    private val mutation = Mutex()
    private var scheduler: Job? = null
    private val previewState = MutableStateFlow<Map<String, List<SubscriptionCandidate>>>(emptyMap())
    val previews = previewState.asStateFlow()
    private val constraintState = MutableStateFlow<Map<String, SubscriptionReason>>(emptyMap())
    val waitingReasons = constraintState.asStateFlow()
    private val profilesState = MutableStateFlow<List<com.openyt.core.domain.DownloadProfile>>(emptyList())
    val profiles = profilesState.asStateFlow()
    val supportsDeviceConditions: Boolean get() = environment.supportsDeviceConditions

    suspend fun restore() {
        current.value = repository.load()
        failures.value = current.value.filter { it.lastFailure != null }.map { it.id }.toSet()
        refreshProfiles()
    }

    suspend fun refreshProfiles() { profilesState.value = profileRepository?.loadProfiles().orEmpty() }

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

    suspend fun add(name: String, url: String, kind: SubscriptionKind, intervalMinutes: Int, includeExisting: Boolean, filters: SubscriptionFilters = SubscriptionFilters()): Boolean = mutation.withLock {
        val validated = UrlValidator().validate(url) as? UrlValidation.Valid ?: return@withLock false
        if (name.isBlank() || intervalMinutes !in 1..10_080 || !filters.isValid()) return@withLock false
        if (current.value.any { it.url == validated.url && it.kind == kind }) return@withLock false
        val rule = SubscriptionRule(nextId(), name.trim().take(120), validated.url, kind, intervalMinutes, includeExisting = includeExisting, filters = filters)
        repository.save(rule)
        current.update { it + rule }
        true
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutation.withLock {
        val rule = current.value.firstOrNull { it.id == id } ?: return@withLock
        val updated = rule.copy(enabled = enabled, revision = rule.revision + 1)
        repository.save(updated)
        current.update { items -> items.map { if (it.id == id) updated else it } }
    }

    suspend fun updateFilters(id: String, filters: SubscriptionFilters): Boolean = mutation.withLock {
        if (!filters.isValid()) return@withLock false
        val rule = current.value.firstOrNull { it.id == id } ?: return@withLock false
        val updated = rule.copy(filters = filters, revision = rule.revision + 1)
        repository.save(updated)
        current.update { items -> items.map { if (it.id == id) updated else it } }
        true
    }

    /** Explicitly requested replay only. Editing a filter never replays rejected history. */
    suspend fun retryCandidate(id: String, episodeId: String) = mutate(id) { rule ->
        val restored = if (rule.candidates.none { it.episode.id == episodeId } && rule.candidates.size < 5_000) previewState.value[id]?.firstOrNull { it.episode.id == episodeId } else null
        if (rule.candidates.none { it.episode.id == episodeId } && restored == null) return@mutate rule
        rule.copy(candidates = (rule.candidates + listOfNotNull(restored)).map { if (it.episode.id == episodeId && it.state != CandidateState.QUEUED) it.copy(state = CandidateState.DISCOVERED, reason = SubscriptionReason.READY) else it }, archivedFilteredIds = rule.archivedFilteredIds - episodeId)
    }

    private suspend fun mutate(id: String, transform: (SubscriptionRule) -> SubscriptionRule) = mutation.withLock {
        val old = current.value.firstOrNull { it.id == id } ?: return@withLock
        val updated = transform(old)
        repository.save(updated)
        current.update { items -> items.map { if (it.id == id) updated else it } }
    }

    suspend fun remove(id: String) = mutation.withLock {
        repository.delete(id)
        current.update { rules -> rules.filterNot { it.id == id } }
        failures.update { it - id }
    }

    suspend fun checkDue(queueOnly: Boolean = false, maximumRules: Int = Int.MAX_VALUE): Int {
        val now = nowMillis()
        val due = current.value.filter { it.enabled && (maxOf(it.lastCheckMillis, it.lastAttemptMillis) == 0L || now - maxOf(it.lastCheckMillis, it.lastAttemptMillis) >= it.intervalMinutes * 60_000L) }
            .sortedWith(compareBy<SubscriptionRule> { maxOf(it.lastCheckMillis, it.lastAttemptMillis) }.thenBy { it.id })
            .take(maximumRules.coerceAtLeast(0))
        due.forEach { check(it.id, queueOnly) }
        return due.count { it.id in failures.value }
    }

    suspend fun check(id: String, queueOnly: Boolean = false): Int = execute(id, preview = false, queueOnly = queueOnly)

    suspend fun preview(id: String) { execute(id, preview = true) }
    suspend fun previewHistory(id: String) { execute(id, preview = true, historyOnly = true) }

    private suspend fun execute(id: String, preview: Boolean, historyOnly: Boolean = false, queueOnly: Boolean = false): Int {
        val acquired = mutation.withLock {
            if (id in checking.value) false else { checking.update { it + id }; true }
        }
        if (!acquired) return 0
        try {
                val rule = current.value.firstOrNull { it.id == id && it.enabled } ?: return 0
                val filters = rule.filters
                if (!filters.isValid()) return 0
                val binding = filters.profileId?.let { profileRepository?.getDestinationBinding(it) }
                val destination = binding?.destinationDirectory ?: directory()
                if (!preview) mutate(id) { it.copy(lastAttemptMillis = nowMillis()) }
                val initialState = environment.state(destination)
                SubscriptionPolicy.networkReason(filters, initialState)?.let { reason ->
                    constraintState.update { it + (id to reason) }
                    return 0
                }
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
                if (!rule.initialized && !rule.includeExisting) {
                    if (!preview) mutate(id) { it.copy(initialized = true, seenIds = it.seenIds + episodes.map { episode -> episode.id }, lastCheckMillis = nowMillis(), lastFailure = null) }
                    if (preview) previewState.update { it + (id to episodes.take(50).map { episode -> SubscriptionCandidate(episode, CandidateState.FILTERED, SubscriptionReason.BASELINE) }) }
                    return 0
                }
                val oldFiltered = rule.candidates.filter { it.state == CandidateState.FILTERED }.dropLast(500)
                val archive = rule.archivedFilteredIds + oldFiltered.map { it.episode.id }
                val removed = oldFiltered.map { it.episode.id }.toSet()
                val candidates = if (historyOnly) episodes.filter { it.id in archive || rule.candidates.any { c -> c.episode.id == it.id && c.state == CandidateState.FILTERED } }
                    .take(200).map { SubscriptionCandidate(it, ruleRevision = rule.revision) }.toMutableList()
                else rule.candidates.filter { it.state != CandidateState.QUEUED && it.episode.id !in removed }.toMutableList()
                val known = candidates.map { it.episode.id }.toSet() + rule.seenIds + archive
                val unseen = episodes.filter { it.id !in known }
                val slots = (5_000 - candidates.size).coerceAtLeast(0)
                val discovered = if (historyOnly || archive.size >= 50_000) emptyList() else unseen.take(minOf(200, slots))
                candidates += discovered.map { SubscriptionCandidate(it, ruleRevision = rule.revision) }
                val limited = unseen.size > discovered.size
                if (!preview) mutate(id) { it.copy(candidates = candidates.toList(), discoveryLimited = limited, archivedFilteredIds = archive) }
                var queued = 0
                var inspected = 0
                var localReservation = 0L
                val results = mutableListOf<SubscriptionCandidate>()
                val eligible = candidates.filter { it.state != CandidateState.FILTERED }
                val start = eligible.indexOfFirst { it.episode.id == rule.nextCandidateId }.coerceAtLeast(0)
                val ordered = eligible.drop(start) + eligible.take(start)
                var nextCandidateId: String? = ordered.firstOrNull()?.episode?.id
                for ((index, candidate) in ordered.take(200).withIndex()) {
                    if (current.value.none { it.id == id && it.enabled && it.revision == rule.revision }) break
                    var episode = candidate.episode
                    var reason = SubscriptionPolicy.networkReason(filters, environment.state(destination))
                    if (reason == null && queued >= filters.maxNewPerCheck) reason = SubscriptionReason.CHECK_LIMIT
                    val validated = UrlValidator().validate(episode.url) as? UrlValidation.Valid
                    if (validated == null) reason = SubscriptionReason.INVALID_URL
                    val needsInspection = filters.profileId != null ||
                        (filters.minimumDurationSeconds != null || filters.maximumDurationSeconds != null) && episode.durationSeconds == null ||
                        filters.minimumFreeBytes != null && episode.sizeBytes == null
                    if (reason == SubscriptionReason.CHECK_LIMIT || reason == null && needsInspection && inspected >= 20) {
                        results += ordered.drop(index).map { it.copy(state = CandidateState.DEFERRED, reason = reason ?: SubscriptionReason.INSPECTION_LIMIT) }
                        nextCandidateId = candidate.episode.id
                        break
                    }
                    var media: MediaInfo? = null
                    if (reason == null && needsInspection) {
                        if (inspected >= 20) reason = SubscriptionReason.INSPECTION_LIMIT
                        else {
                            inspected++
                            when (val inspection = engine.inspect(episode.url)) {
                                is EngineResult.Failure -> reason = SubscriptionReason.INSPECTION_FAILED
                                is EngineResult.Success -> {
                                    media = (inspection.value as? Inspection.Single)?.media
                                    if (media == null) reason = SubscriptionReason.INSPECTION_FAILED
                                    else episode = episode.copy(durationSeconds = media.durationSeconds ?: episode.durationSeconds, sizeBytes = media.formats.mapNotNull { it.sizeBytes }.maxOrNull() ?: episode.sizeBytes)
                                }
                            }
                        }
                    }
                    val prefix = "$id:"
                    val pending = coordinator.tasks.value.filter { it.request.options.originKey?.startsWith(prefix) == true && (it.status.isActive || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.INTERRUPTED) }
                    val totalReservations = coordinator.tasks.value.filter { it.status.isActive || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.INTERRUPTED }
                        .fold(0L) { sum, task -> (sum + task.request.options.subscriptionReservedBytes.coerceAtMost(Long.MAX_VALUE - sum)).coerceAtLeast(0) }
                    if (reason == null) reason = SubscriptionPolicy.evaluate(filters, episode, environment.state(destination), maxOf(totalReservations, localReservation))
                    if (reason == SubscriptionReason.READY && pending.size >= filters.maxPending) reason = SubscriptionReason.PENDING_LIMIT
                    if (reason == SubscriptionReason.READY && queued >= filters.maxNewPerCheck) reason = SubscriptionReason.CHECK_LIMIT
                    val kind = if (rule.kind == SubscriptionKind.FEED) MediaKind.AUDIO else MediaKind.VIDEO
                    val actualMedia = media ?: MediaInfo(episode.id, if (rule.kind == SubscriptionKind.FEED) "podcast" else "subscription", episode.title, durationSeconds = episode.durationSeconds, webpageUrl = episode.url)
                    var request = DownloadRequest(episode.url, kind, destinationDirectory = destination,
                        options = DownloadOptions(originKey = "$id:${episode.id}", subscriptionReservedBytes = episode.sizeBytes?.coerceAtLeast(0) ?: 0, exportTargetUri = binding?.storageTreeUri ?: exportTargetUri()))
                    if (reason == SubscriptionReason.READY && filters.profileId != null) {
                        val profile = profileRepository?.getProfile(filters.profileId)
                        if (profile == null) reason = SubscriptionReason.PROFILE_UNAVAILABLE
                        else when (val resolution = DownloadProfileResolver.resolve(profile, actualMedia)) {
                            is ProfileResolution.Incompatible -> reason = SubscriptionReason.PROFILE_INCOMPATIBLE
                            is ProfileResolution.Success -> request = resolution.plan.toDownloadRequest(episode.url, destination, exportTargetUri = binding?.storageTreeUri ?: exportTargetUri())
                                .let { it.copy(options = it.options.copy(originKey = "$id:${episode.id}", subscriptionReservedBytes = episode.sizeBytes?.coerceAtLeast(0) ?: 0)) }
                        }
                    }
                    request = request.copy(options = request.options.copy(executionConstraints = DownloadExecutionConstraints(
                        wifiOnly = filters.wifiOnly, unmeteredOnly = filters.unmeteredOnly, chargingOnly = filters.chargingOnly, minimumFreeBytes = filters.minimumFreeBytes, requireAppOpen = queueOnly)))
                    var result = candidate.copy(episode = episode, ruleRevision = rule.revision,
                        state = if (reason == SubscriptionReason.DURATION_FILTER || reason == SubscriptionReason.INVALID_URL) CandidateState.FILTERED else CandidateState.DEFERRED,
                        reason = reason ?: SubscriptionReason.CAPABILITY_UNKNOWN)
                    if (reason == SubscriptionReason.READY) {
                        if (preview) result = result.copy(state = CandidateState.DISCOVERED)
                        else {
                            val taskId = mutation.withLock {
                                if (current.value.any { it.id == id && it.enabled && it.revision == rule.revision }) coordinator.enqueue(actualMedia, request) else null
                            } ?: break
                            kotlin.check(coordinator.awaitPersistence()) { "queue persistence unavailable" }
                            result = result.copy(state = CandidateState.QUEUED, reason = SubscriptionReason.QUEUED, taskId = taskId)
                            mutate(id) { it.copy(seenIds = it.seenIds + episode.id, candidates = it.candidates.map { old -> if (old.episode.id == episode.id) result else old }) }
                        }
                        queued++
                        localReservation += request.options.subscriptionReservedBytes.coerceAtMost(Long.MAX_VALUE - localReservation)
                    }
                    results += result
                    nextCandidateId = ordered.getOrNull(index + 1)?.episode?.id ?: ordered.firstOrNull()?.episode?.id
                }
                if (preview) previewState.update { it + (id to results.take(200)) }
                if (!preview) mutate(id) { stored ->
                    val changes = results.associateBy { it.episode.id }
                    if (stored.revision != rule.revision) stored
                    else stored.copy(initialized = true, lastCheckMillis = nowMillis(), candidates = stored.candidates.map { changes[it.episode.id] ?: it }, nextCandidateId = nextCandidateId, lastFailure = null)
                }
                constraintState.update { it - id }
                failures.update { it - id }
                return if (preview) 0 else queued
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!preview) {
                try { mutate(id) { it.copy(lastFailure = SubscriptionReason.INSPECTION_FAILED) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* The queue remains unacknowledged and can be retried. */ }
            }
            failures.update { it + id }
            return 0
        } finally { checking.update { it - id } }
    }
}
