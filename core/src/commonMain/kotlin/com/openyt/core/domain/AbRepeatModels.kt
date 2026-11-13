package com.openyt.core.domain

/**
 * Lifecycle state of an active-session A/B repetition loop. Saved study ranges
 * never auto-start playback or repeat after restart; transition to [ACTIVE]
 * only happens through explicit session actions.
 */
enum class AbRepeatStatus {
    INACTIVE,
    READY,
    ACTIVE,
    PAUSED,
    UNSUPPORTED,
}

/**
 * Structured error taxonomy explaining why A/B repetition could not be set,
 * started, or continued on the active playback session.
 */
sealed interface AbRepeatFailure {
    val message: String

    data class PlayerStopped(
        override val message: String = "Playback session is stopped",
    ) : AbRepeatFailure

    data class PlayerNotReady(
        override val message: String = "Playback session is still preparing",
    ) : AbRepeatFailure

    data class PlayerError(
        override val message: String = "Playback session is in an error state",
    ) : AbRepeatFailure

    data class CapabilityUnsupported(
        override val message: String,
    ) : AbRepeatFailure

    data class UnknownDuration(
        override val message: String = "A/B repeat requires a known media duration",
    ) : AbRepeatFailure

    data class InvalidRange(
        val validationError: StudyValidationError? = null,
        override val message: String,
    ) : AbRepeatFailure

    data class NoRangeConfigured(
        override val message: String = "Set both A and B points before starting repeat",
    ) : AbRepeatFailure

    data class EntryMismatch(
        val expectedEntryId: String,
        val actualEntryId: String?,
        override val message: String =
            "Loaded playback item ($actualEntryId) does not match range entry ($expectedEntryId)",
    ) : AbRepeatFailure

    data class SeekFailed(
        override val message: String = "Player could not seek back to point A",
    ) : AbRepeatFailure
}

sealed interface AbRepeatResult<out T> {
    data class Success<T>(val value: T) : AbRepeatResult<T>
    data class Failure(val failure: AbRepeatFailure) : AbRepeatResult<Nothing>
}

/**
 * Observable session state for explicit A/B repetition bound to a loaded media
 * item and playback [sessionGeneration].
 */
data class StudyAbRepeatState(
    val status: AbRepeatStatus = AbRepeatStatus.INACTIVE,
    val isSupported: Boolean = true,
    val disabledReason: String? = null,
    val entryId: String? = null,
    val mediaPath: String? = null,
    val sessionGeneration: Long = 0L,
    val knownDurationMillis: Long? = null,
    val pointAMillis: Long? = null,
    val pointBMillis: Long? = null,
    val activeSavedRangeId: String? = null,
    val loopCount: Int = 0,
    val lastError: AbRepeatFailure? = null,
) {
    val hasPointA: Boolean
        get() = pointAMillis != null

    val hasValidRange: Boolean
        get() = pointAMillis != null &&
            pointBMillis != null &&
            pointBMillis > pointAMillis &&
            (pointBMillis - pointAMillis) >= 1_000L

    val isLooping: Boolean
        get() = status == AbRepeatStatus.ACTIVE || status == AbRepeatStatus.PAUSED
}
