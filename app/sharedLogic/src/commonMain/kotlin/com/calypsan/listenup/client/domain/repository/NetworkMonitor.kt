package com.calypsan.listenup.client.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for monitoring network connectivity.
 *
 * Used by SearchRepository to determine whether to use server search
 * (online) or fall back to local FTS search (offline).
 *
 * Also used by DownloadManager to enforce WiFi-only download constraint.
 *
 * "Online" means the device's active network offers a usable route — NOT that the public
 * internet was reached. ListenUp servers are self-hosted and are routinely LAN-only. Whether the
 * server responds is decided by `ConnectionHealthStore`, not here.
 *
 * Platform-specific implementations:
 * - Android: ConnectivityManager with NetworkCallback
 * - iOS: `NWPathMonitor`
 */
interface NetworkMonitor {
    /**
     * Current online status.
     *
     * Returns true if the device's active network offers a usable route.
     * This is a snapshot—use [isOnlineFlow] for reactive updates.
     */
    fun isOnline(): Boolean

    /**
     * Observable network state.
     *
     * Emits true when a usable route is available on the active network, false when it is lost.
     * Use this for reactive UI updates (e.g., showing offline indicator).
     */
    val isOnlineFlow: StateFlow<Boolean>

    /**
     * Observable unmetered network state (WiFi, ethernet).
     *
     * Emits true when connected to an unmetered network (WiFi, ethernet),
     * false when on metered (cellular) or offline.
     *
     * Used by download queue to show "Waiting for WiFi" state when
     * WiFi-only downloads is enabled but device is on cellular.
     */
    val isOnUnmeteredNetworkFlow: StateFlow<Boolean>
}
