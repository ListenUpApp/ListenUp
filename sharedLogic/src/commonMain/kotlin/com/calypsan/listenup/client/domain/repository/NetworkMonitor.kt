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
 * Platform-specific implementations:
 * - Android: ConnectivityManager with NetworkCallback
 * - iOS: NWPathMonitor (future implementation)
 */
interface NetworkMonitor {
    /**
     * Current online status.
     *
     * Returns true if the device has internet connectivity.
     * This is a snapshot—use [isOnlineFlow] for reactive updates.
     */
    fun isOnline(): Boolean

    /**
     * Observable network state.
     *
     * Emits true when network becomes available, false when lost.
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
