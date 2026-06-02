package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Display shape for an active session, returned by AuthService.listSessions.
 * `current = true` marks the session belonging to the caller's current JWT.
 */
@Serializable
data class SessionSummary(
    @SerialName("id")
    val id: SessionId,
    @SerialName("label")
    val label: String?,
    @SerialName("deviceInfo")
    val deviceInfo: DeviceInfo? = null,
    @SerialName("userAgent")
    val userAgent: String? = null,
    @SerialName("createdAt")
    val createdAt: Long, // unix millis
    @SerialName("lastUsedAt")
    val lastUsedAt: Long, // unix millis
    @SerialName("current")
    val current: Boolean,
)
