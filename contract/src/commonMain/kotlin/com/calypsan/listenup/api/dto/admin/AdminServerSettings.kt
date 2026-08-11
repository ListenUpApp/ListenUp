package com.calypsan.listenup.api.dto.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The server-wide editable identity settings an admin manages: the display [serverName]
 * (also surfaced pre-auth via `InstanceService.getServerInfo`), the optional public
 * [remoteUrl] (null when unset), the [inboxEnabled] scanner gate for the single
 * library, and the [pushNotificationsEnabled] toggle (also surfaced pre-auth via
 * `ServerInfo.pushEnabled`, ANDed there with whether a relay is configured).
 */
@Serializable
data class AdminServerSettings(
    @SerialName("serverName") val serverName: String,
    @SerialName("remoteUrl") val remoteUrl: String?,
    @SerialName("inboxEnabled") val inboxEnabled: Boolean,
    @SerialName("pushNotificationsEnabled") val pushNotificationsEnabled: Boolean = true,
)

/**
 * Partial update for [AdminServerSettings] (PATCH semantics). A null field is left
 * unchanged. To clear [remoteUrl], send an empty string `""` (distinguishable from null).
 */
@Serializable
data class AdminServerSettingsPatch(
    @SerialName("serverName") val serverName: String? = null,
    @SerialName("remoteUrl") val remoteUrl: String? = null,
    @SerialName("inboxEnabled") val inboxEnabled: Boolean? = null,
    @SerialName("pushNotificationsEnabled") val pushNotificationsEnabled: Boolean? = null,
)
