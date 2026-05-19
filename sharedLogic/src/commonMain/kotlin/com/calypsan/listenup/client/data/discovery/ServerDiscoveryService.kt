package com.calypsan.listenup.client.data.discovery

import kotlinx.coroutines.flow.Flow

/**
 * Represents a server discovered on the local network via mDNS/Zeroconf.
 *
 * Contains information extracted from the mDNS TXT records:
 * - id: Server's unique identifier
 * - name: Human-readable server name
 * - apiVersion: API version (e.g., "v1")
 * - serverVersion: Server version (e.g., "1.0.0")
 * - remoteUrl: Optional remote URL for external access
 *
 * The local URL is constructed from the discovered host and port.
 */
data class DiscoveredServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val apiVersion: String,
    val serverVersion: String,
    val remoteUrl: String? = null,
) {
    /**
     * Local network URL for connecting to this server.
     */
    val localUrl: String get() = if (":" in host) "http://[$host]:$port" else "http://$host:$port"
}

/**
 * Service for discovering ListenUp servers on the local network.
 *
 * Uses platform-specific service discovery:
 * - Android: NsdManager (Network Service Discovery)
 * - iOS: NSNetServiceBrowser (Bonjour)
 *
 * The service emits a list of currently visible servers. Servers appear
 * when discovered and disappear when they go offline.
 */
interface ServerDiscoveryService {
    /**
     * Flow of currently discovered servers on the local network.
     *
     * Emits an updated list whenever servers appear or disappear.
     * The flow continues until cancelled.
     *
     * Note: Discovery requires the app to be in the foreground on most platforms.
     */
    fun discover(): Flow<List<DiscoveredServer>>

    /**
     * Start discovery in the background.
     * Call this when the app becomes active.
     */
    fun startDiscovery()

    /**
     * Stop discovery.
     * Call this when the app goes to background to conserve battery.
     */
    fun stopDiscovery()
}
