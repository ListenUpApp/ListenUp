package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured device metadata a first-party client sends when it creates a session
 * (login/register/setup/invite-claim). All fields optional — third-party REST callers
 * may omit any. Intentionally excludes browser_*, client_build, and ip_address.
 */
@Serializable
data class DeviceInfo(
    @SerialName("deviceType") val deviceType: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("platformVersion") val platformVersion: String? = null,
    @SerialName("clientName") val clientName: String? = null,
    @SerialName("clientVersion") val clientVersion: String? = null,
    @SerialName("deviceName") val deviceName: String? = null,
    @SerialName("deviceModel") val deviceModel: String? = null,
) {
    init {
        require(
            listOf(deviceType, platform, platformVersion, clientName, clientVersion, deviceName, deviceModel)
                .all { it == null || it.length <= DEVICE_FIELD_MAX },
        ) { "device info field too long" }
    }
}

const val DEVICE_FIELD_MAX = 128
