package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Display shape for an active session, returned by AuthService.listSessions.
 * `current = true` marks the session belonging to the caller's current JWT.
 * No client UI ships in Phase 1; the contract is ready for it.
 */
@Serializable
data class SessionSummary(
    @SerialName("id")
    val id: SessionId,
    val label: String?,
    val createdAt: Long, // unix millis
    val lastUsedAt: Long, // unix millis
    val current: Boolean,
)
