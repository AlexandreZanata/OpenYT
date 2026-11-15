package com.openyt.core.automation

import kotlinx.serialization.Serializable
import com.openyt.core.application.DownloadExecutionGate
import com.openyt.core.domain.DownloadRequest

@Serializable
enum class CandidateState { DISCOVERED, FILTERED, DEFERRED, QUEUED }

@Serializable
enum class SubscriptionReason {
    READY, BASELINE, DURATION_FILTER, UNKNOWN_DURATION, UNKNOWN_SIZE, OFFLINE,
    WIFI_REQUIRED, UNMETERED_REQUIRED, CHARGING_REQUIRED, CAPABILITY_UNKNOWN,
    LOW_SPACE, CHECK_LIMIT, PENDING_LIMIT, INSPECTION_LIMIT, INSPECTION_FAILED,
    PROFILE_UNAVAILABLE, PROFILE_INCOMPATIBLE, INVALID_URL, QUEUED, DISCOVERY_LIMIT,
}

@Serializable
data class SubscriptionCandidate(
    val episode: FeedEpisode,
    val state: CandidateState = CandidateState.DISCOVERED,
    val reason: SubscriptionReason = SubscriptionReason.READY,
    val ruleRevision: Int = 1,
    val taskId: String? = null,
)

@Serializable
data class SubscriptionFilters(
    val minimumDurationSeconds: Long? = null,
    val maximumDurationSeconds: Long? = null,
    val profileId: String? = null,
    val maxNewPerCheck: Int = 20,
    val maxPending: Int = 100,
    val wifiOnly: Boolean = false,
    val unmeteredOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val minimumFreeBytes: Long? = null,
) {
    fun isValid(): Boolean =
        (minimumDurationSeconds == null || minimumDurationSeconds >= 0) &&
            (maximumDurationSeconds == null || maximumDurationSeconds >= 0) &&
            (minimumDurationSeconds == null || maximumDurationSeconds == null || minimumDurationSeconds <= maximumDurationSeconds) &&
            maxNewPerCheck in 1..100 && maxPending in 1..1_000 &&
            (minimumFreeBytes == null || minimumFreeBytes >= 0) &&
            (profileId == null || profileId.isNotBlank())
}

/** null means unknown/unsupported, never satisfied. Wi-Fi and unmetered are distinct. */
data class SubscriptionDeviceState(
    val connected: Boolean?,
    val wifi: Boolean? = null,
    val unmetered: Boolean? = null,
    val charging: Boolean? = null,
    val availableBytes: Long? = null,
)

fun interface SubscriptionEnvironment {
    suspend fun state(destinationDirectory: String): SubscriptionDeviceState
    val supportsDeviceConditions: Boolean get() = false
}

object SubscriptionPolicy {
    fun networkReason(filters: SubscriptionFilters, state: SubscriptionDeviceState): SubscriptionReason? {
        if (state.connected == false) return SubscriptionReason.OFFLINE
        if (state.connected == null) return SubscriptionReason.CAPABILITY_UNKNOWN
        if (filters.wifiOnly && state.wifi != true) return if (state.wifi == null) SubscriptionReason.CAPABILITY_UNKNOWN else SubscriptionReason.WIFI_REQUIRED
        if (filters.unmeteredOnly && state.unmetered != true) return if (state.unmetered == null) SubscriptionReason.CAPABILITY_UNKNOWN else SubscriptionReason.UNMETERED_REQUIRED
        if (filters.chargingOnly && state.charging != true) return if (state.charging == null) SubscriptionReason.CAPABILITY_UNKNOWN else SubscriptionReason.CHARGING_REQUIRED
        return null
    }

    fun evaluate(filters: SubscriptionFilters, episode: FeedEpisode, state: SubscriptionDeviceState, reservedBytes: Long = 0): SubscriptionReason {
        networkReason(filters, state)?.let { return it }
        if (filters.minimumDurationSeconds != null || filters.maximumDurationSeconds != null) {
            val duration = episode.durationSeconds ?: return SubscriptionReason.UNKNOWN_DURATION
            if (duration < 0 || filters.minimumDurationSeconds?.let { duration < it } == true || filters.maximumDurationSeconds?.let { duration > it } == true) {
                return SubscriptionReason.DURATION_FILTER
            }
        }
        filters.minimumFreeBytes?.let { reserve ->
            val available = state.availableBytes ?: return SubscriptionReason.CAPABILITY_UNKNOWN
            val size = episode.sizeBytes?.takeIf { it >= 0 } ?: return SubscriptionReason.UNKNOWN_SIZE
            // Subtractions avoid overflowing untrusted metadata size totals.
            if (available < reserve || available - reserve < reservedBytes || available - reserve - reservedBytes < size) return SubscriptionReason.LOW_SPACE
        }
        return SubscriptionReason.READY
    }
}

class SubscriptionDownloadGate(private val environment: SubscriptionEnvironment, private val isAppOpen: () -> Boolean = { true }) : DownloadExecutionGate {
    override suspend fun canStart(request: DownloadRequest, reservedBytes: Long): Boolean = evaluate(request, reservedBytes, false)
    override suspend fun canContinue(request: DownloadRequest): Boolean = evaluate(request, 0, true)
    private suspend fun evaluate(request: DownloadRequest, reservedBytes: Long, running: Boolean): Boolean {
        val saved = request.options.executionConstraints ?: return true
        if (saved.requireAppOpen && !isAppOpen()) return false
        val filters = SubscriptionFilters(wifiOnly = saved.wifiOnly, unmeteredOnly = saved.unmeteredOnly, chargingOnly = saved.chargingOnly, minimumFreeBytes = saved.minimumFreeBytes)
        return SubscriptionPolicy.evaluate(filters, FeedEpisode("", "", request.url, sizeBytes = if (running) 0 else request.options.subscriptionReservedBytes), environment.state(request.destinationDirectory), reservedBytes) == SubscriptionReason.READY
    }
}
