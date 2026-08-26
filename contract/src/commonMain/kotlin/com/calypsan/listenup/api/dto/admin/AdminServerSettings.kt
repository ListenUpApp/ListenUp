package com.calypsan.listenup.api.dto.admin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * The server-wide editable identity settings an admin manages: the display [serverName]
 * (also surfaced pre-auth via `InstanceService.getServerInfo`), the optional public
 * [remoteUrl] (null when unset), the [holdNewBooksForReview] scanner gate for the single
 * library, and the [pushNotificationsEnabled] toggle (also surfaced pre-auth via
 * `ServerInfo.pushEnabled`, ANDed there with whether a relay is configured).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminServerSettings(
    @SerialName("serverName") val serverName: String,
    @SerialName("remoteUrl") val remoteUrl: String?,
    // Reads the old `inboxEnabled` too, and defaults when neither is present. A self-hosted server
    // updates independently of the apps installed against it, so BOTH skew directions are normal:
    // without the alias an older client hits a SerializationException on this response, and without
    // the default a newer client talking to an older server sees the field vanish. The rename was
    // for honesty; it shouldn't cost anyone a broken settings screen.
    @SerialName("holdNewBooksForReview")
    @JsonNames("inboxEnabled")
    val holdNewBooksForReview: Boolean = false,
    @SerialName("pushNotificationsEnabled") val pushNotificationsEnabled: Boolean = true,
    /** Whether the server writes `listenup.json` curation sidecars beside books. Default on. */
    @SerialName("sidecarWritesEnabled") val sidecarWritesEnabled: Boolean = true,
)

/**
 * Partial update for [AdminServerSettings] (PATCH semantics). A null field is left
 * unchanged. To clear [remoteUrl], send an empty string `""` (distinguishable from null).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AdminServerSettingsPatch(
    @SerialName("serverName") val serverName: String? = null,
    @SerialName("remoteUrl") val remoteUrl: String? = null,
    @SerialName("holdNewBooksForReview")
    @JsonNames("inboxEnabled")
    val holdNewBooksForReview: Boolean? = null,
    @SerialName("pushNotificationsEnabled") val pushNotificationsEnabled: Boolean? = null,
    /** Toggles `listenup.json` curation-sidecar writes. Null leaves the setting unchanged. */
    @SerialName("sidecarWritesEnabled") val sidecarWritesEnabled: Boolean? = null,
)
