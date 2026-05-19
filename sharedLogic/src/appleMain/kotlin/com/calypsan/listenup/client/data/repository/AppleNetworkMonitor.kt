@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

private val logger = KotlinLogging.logger {}

/**
 * iOS implementation of NetworkMonitor using NWPathMonitor.
 *
 * NWPathMonitor is Apple's modern network monitoring API that provides
 * real-time updates about network path changes including:
 * - Connectivity status (online/offline)
 * - Network type (WiFi, cellular, ethernet)
 * - Whether the connection is "expensive" (metered)
 *
 * The monitor runs on the main queue and updates state flows when
 * network conditions change.
 */
class AppleNetworkMonitor : NetworkMonitor {
    private val pathMonitor: nw_path_monitor_t = nw_path_monitor_create()

    private val _isOnlineFlow = MutableStateFlow(false)
    override val isOnlineFlow: StateFlow<Boolean> = _isOnlineFlow.asStateFlow()

    private val _isOnUnmeteredNetworkFlow = MutableStateFlow(false)
    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean> = _isOnUnmeteredNetworkFlow.asStateFlow()

    init {
        logger.debug { "Initializing iOS network monitor" }

        nw_path_monitor_set_update_handler(pathMonitor) { path ->
            if (path != null) {
                val status = nw_path_get_status(path)
                val isOnline = status == nw_path_status_satisfied
                val isExpensive = nw_path_is_expensive(path)

                logger.debug { "Network path updated: online=$isOnline, expensive=$isExpensive" }

                _isOnlineFlow.value = isOnline
                // Unmetered = online AND not expensive (WiFi/ethernet, not cellular)
                _isOnUnmeteredNetworkFlow.value = isOnline && !isExpensive
            }
        }

        nw_path_monitor_set_queue(pathMonitor, dispatch_get_main_queue())
        nw_path_monitor_start(pathMonitor)

        logger.info { "iOS network monitor started" }
    }

    override fun isOnline(): Boolean = _isOnlineFlow.value

    /**
     * Stop monitoring and release resources.
     *
     * Call this when the monitor is no longer needed to prevent memory leaks.
     */
    fun stop() {
        logger.info { "Stopping iOS network monitor" }
        nw_path_monitor_cancel(pathMonitor)
    }
}
