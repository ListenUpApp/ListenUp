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
    /**
     * All local-network candidate URLs for this server, best-first (a multi-homed server resolves to
     * several). [localUrl] is the first of these; the connect path tries each so an unreachable
     * primary falls back to a reachable LAN address. Empty when the server has no local URL.
     */
    val localUrls: List<String> = emptyList(),
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
