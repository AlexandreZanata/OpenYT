package com.openyt.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.openyt.core.automation.SubscriptionDeviceState
import com.openyt.core.automation.SubscriptionEnvironment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSubscriptionEnvironment(context: Context) : SubscriptionEnvironment {
    override val supportsDeviceConditions: Boolean = true
    private val context = context.applicationContext
    override suspend fun state(destinationDirectory: String): SubscriptionDeviceState = withContext(Dispatchers.IO) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        SubscriptionDeviceState(
            connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            charging = status?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL },
            availableBytes = runCatching { File(destinationDirectory).takeIf { it.isDirectory && it.canWrite() }?.usableSpace }.getOrNull(),
        )
    }
}
