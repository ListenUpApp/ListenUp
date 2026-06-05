package com.calypsan.listenup.api.dto.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The server-wide editable identity settings an admin manages: the display [serverName]
 * (also surfaced pre-auth via `InstanceService.getServerInfo`) and the optional public
 * [remoteUrl]. [remoteUrl] is null when unset.
 */
@Serializable
data class AdminServerSettings(
    @SerialName("serverName") val serverName: String,
    @SerialName("remoteUrl") val remoteUrl: String?,
)

/**
 * Partial update for [AdminServerSettings] (PATCH semantics). A null field is left
 * unchanged. To clear [remoteUrl], send an empty string `""` (distinguishable from null).
 */
@Serializable
data class AdminServerSettingsPatch(
    @SerialName("serverName") val serverName: String? = null,
    @SerialName("remoteUrl") val remoteUrl: String? = null,
)
