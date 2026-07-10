package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.push.PushPlatform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * REST request body for [com.calypsan.listenup.api.PushService.registerToken]
 * (`POST /api/v1/push/tokens`). The Kotlin RPC surface takes [token]/[platform] as
 * separate suspend-fun parameters; this wraps them for JSON transport.
 */
@Serializable
@SerialName("RegisterPushTokenBody")
data class RegisterPushTokenBody(
    /** The device's push token. */
    @SerialName("token") val token: String,
    /** The device's push delivery platform. */
    @SerialName("platform") val platform: PushPlatform,
)
