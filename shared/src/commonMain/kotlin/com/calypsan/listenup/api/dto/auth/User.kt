package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.Serializable

/**
 * The public-facing user shape. Auth identity only — profile data lives in a
 * future profile domain. `email` preserves the user's original capitalization;
 * normalized lookup happens server-side via `email_normalized`.
 */
@Serializable
data class User(
    val id: UserId,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val status: UserStatus,
    val createdAt: Long, // unix millis
)
