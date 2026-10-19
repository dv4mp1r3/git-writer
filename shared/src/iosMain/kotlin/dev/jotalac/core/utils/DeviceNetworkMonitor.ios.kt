package dev.jotalac.core.utils

import dev.jordond.connectivity.Connectivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The network state from iOS itself - it calls back when the network changes, nothing is polled. */
class DeviceNetworkMonitor : NetworkMonitor {
    private val connectivity = Connectivity { autoStart = true }

    override fun networkAvailability(): Flow<Boolean?> =
        connectivity.statusUpdates.map { it.isConnected }.distinctUntilChanged()
}
