package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The public-facing user shape. Auth identity only — profile data lives in a
 * future profile domain. `email` preserves the user's original capitalization;
 * normalized lookup happens server-side via `email_normalized`.
 */
@Serializable
data class User(
    @SerialName("id")
    val id: UserId,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val status: UserStatus,
    val createdAt: Long, // unix millis
    val permissions: UserPermissions = UserPermissions(),
    val approvedBy: String? = null, // admin user id who approved a PENDING_APPROVAL registration
    val approvedAt: Long? = null, // unix millis
)
