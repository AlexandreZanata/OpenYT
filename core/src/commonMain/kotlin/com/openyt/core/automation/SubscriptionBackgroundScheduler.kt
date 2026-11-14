package com.openyt.core.automation

import kotlinx.coroutines.flow.StateFlow

/** Opt-in bounded discovery. It never promises closed-app download execution. */
interface SubscriptionBackgroundScheduler {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}
