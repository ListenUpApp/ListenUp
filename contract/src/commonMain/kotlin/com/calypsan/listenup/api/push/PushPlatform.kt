package com.calypsan.listenup.api.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Push delivery platform of a registered device token. */
@Serializable
enum class PushPlatform {
    /** Firebase Cloud Messaging (Android). */
    @SerialName("android")
    ANDROID,

    /** Apple Push Notification service (iOS — relay support pending). */
    @SerialName("ios")
    IOS,
}
