package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The public-facing user shape. Auth identity only — profile data lives in a
 * future profile domain. `email` preserves the user's original capitalization;
 * normalized lookup happens server-side via `email_normalized`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class User(
    @SerialName("id")
    val id: UserId,
    val email: String,
    val displayName: String,
    /**
     * An older app reads a role it has never heard of as [UserRole.MEMBER] (least privilege: it hides
     * admin screens; the server still enforces the real role). Always encoded so an app built before
     * this default existed still finds the key.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val role: UserRole = UserRole.MEMBER,
    /**
     * An older app reads a status it has never heard of as [UserStatus.ACTIVE]: this user arrives inside
     * a session the server just issued, and the server stays the authority on whether it is allowed.
     * Always encoded so an app built before this default existed still finds the key.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val status: UserStatus = UserStatus.ACTIVE,
    val createdAt: Long, // unix millis
    val permissions: UserPermissions = UserPermissions(),
    val approvedBy: String? = null, // admin user id who approved a PENDING_APPROVAL registration
    val approvedAt: Long? = null, // unix millis
)
