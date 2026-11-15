package com.openyt.android

import android.content.Context
import androidx.work.*
import com.openyt.core.automation.SubscriptionBackgroundScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Same process/container as the UI. Opt-in, bounded discovery; no foreground download service. */
class AndroidSubscriptionScheduler(context: Context) : SubscriptionBackgroundScheduler {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean(ENABLED, false))
    override val enabled = state.asStateFlow()
    override suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        check(preferences.edit().putBoolean(ENABLED, enabled).commit())
        state.value = enabled
        val manager = WorkManager.getInstance(context)
        val operation = if (enabled) manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SubscriptionDiscoveryWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()) else manager.cancelUniqueWork(WORK_NAME)
        runInterruptible { operation.result.get(30, TimeUnit.SECONDS) }
        state.value = enabled
    }
    companion object {
        const val WORK_NAME = "openyt-subscription-discovery"
        const val PREFERENCES = "subscription-background"
        const val ENABLED = "enabled"
        fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    }
}

class SubscriptionDiscoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AndroidSubscriptionScheduler.isEnabled(applicationContext)) return Result.success()
        val container = (applicationContext as OpenYtApplication).container
        return try {
            withTimeoutOrNull(120_000) {
                if (!container.awaitInitialization()) return@withTimeoutOrNull Result.retry()
                if (!AndroidSubscriptionScheduler.isEnabled(applicationContext)) return@withTimeoutOrNull Result.success()
                val failures = container.subscriptions?.checkDue(queueOnly = true, maximumRules = 20) ?: return@withTimeoutOrNull Result.retry()
                if (container.coordinator.awaitPersistence() && failures == 0) Result.success() else Result.retry()
            } ?: Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
