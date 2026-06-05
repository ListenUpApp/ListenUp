package com.calypsan.listenup.client.domain.model

/** Server-identity settings shown on the admin screen: display name + optional public remote URL. */
data class ServerSettings(
    val serverName: String,
    val remoteUrl: String?,
)
