package com.openyt.data

import com.openyt.core.automation.SubscriptionDeviceState
import com.openyt.core.automation.SubscriptionEnvironment
import java.io.File
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Desktop cannot reliably infer Wi-Fi, metering or battery from interface names. */
class JvmSubscriptionEnvironment : SubscriptionEnvironment {
    override suspend fun state(destinationDirectory: String): SubscriptionDeviceState = withContext(Dispatchers.IO) {
        val connected = runCatching { NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && !it.isLoopback && it.inetAddresses.hasMoreElements() } }.getOrNull()
        val available = runCatching { File(destinationDirectory).takeIf { it.isDirectory && it.canWrite() }?.usableSpace }.getOrNull()
        SubscriptionDeviceState(connected = connected, availableBytes = available)
    }
}
