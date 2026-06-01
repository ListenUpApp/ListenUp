package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SSE payload for `GET /api/v1/auth/registration-status/{userId}/stream`. [status] is one of
 * "pending" | "approved" | "denied"; [message] carries the denial reason.
 */
@Serializable
data class RegistrationStatusEvent(
    @SerialName("status") val status: String,
    @SerialName("timestamp") val timestamp: String? = null,
    @SerialName("message") val message: String? = null,
)
