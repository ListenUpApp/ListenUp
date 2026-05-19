package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing a ListenUp server.
 *
 * This is a presentation-friendly model containing all information
 * needed to display and select servers in the UI.
 */
data class Server(
    val id: String,
    val name: String,
    val apiVersion: String,
    val serverVersion: String,
    val localUrl: String?,
    val remoteUrl: String?,
    val isActive: Boolean,
    val lastSeenAt: Long,
) {
    /**
     * Get the best URL to use for connecting to this server.
     * Prefers local URL when available, falls back to remote.
     */
    fun getBestUrl(): String? = localUrl ?: remoteUrl
}

/**
 * Server with its current online/offline status.
 *
 * Combines a persisted server with discovery state to show
 * whether the server is currently reachable on the local network.
 */
data class ServerWithStatus(
    val server: Server,
    val isOnline: Boolean,
)

/**
 * Represents a server discovered on the local network via mDNS.
 *
 * This is a transient discovery result before the server is
 * persisted or selected by the user.
 */
data class DiscoveredServer(
    val id: String,
    val name: String,
    val localUrl: String,
    val apiVersion: String,
    val serverVersion: String,
    val remoteUrl: String? = null,
)
