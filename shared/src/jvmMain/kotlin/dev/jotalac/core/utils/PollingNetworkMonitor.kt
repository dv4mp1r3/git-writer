package dev.jotalac.core.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Desktop has no OS signal for the network (the JVM has no API for it), so it cannot tell (null) and asks
 * for a check of the remote every [interval] - one cheap request, plus the checks when the window gets focus.
 * It doesnt ping anything, it just decides when to ping
 */
class PollingNetworkMonitor(private val interval: Duration = 1.minutes) : NetworkMonitor {

    override fun networkAvailability(): Flow<Boolean?> = flow {
        while (true) {
            emit(null)
            delay(interval)
        }
    }
}
