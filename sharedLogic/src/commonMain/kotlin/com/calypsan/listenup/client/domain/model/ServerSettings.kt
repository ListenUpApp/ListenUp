package com.calypsan.listenup.client.domain.model

/**
 * Server-identity settings shown on the admin screen: display name, optional public remote URL,
 * the server-wide inbox quarantine gate, and the push-notifications admin toggle.
 */
data class ServerSettings(
    val serverName: String,
    val remoteUrl: String?,
    val inboxEnabled: Boolean = false,
    val pushNotificationsEnabled: Boolean = true,
)
