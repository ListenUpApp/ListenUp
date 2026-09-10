package com.calypsan.listenup.api.dto.invite

import com.calypsan.listenup.api.dto.auth.UserRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A full invite as returned to an admin on creation/listing. */
@Serializable
data class InviteDto(
    @SerialName("id") val id: InviteId,
    @SerialName("code") val code: String,
    @SerialName("email") val email: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("role") val role: UserRole,
    @SerialName("createdBy") val createdBy: String,
    @SerialName("expiresAt") val expiresAt: Long,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("claimedAt") val claimedAt: Long? = null,
    @SerialName("claimedBy") val claimedBy: String? = null,
)

/** Admin-list row: an invite plus a derived [status]. */
@Serializable
data class InviteSummary(
    @SerialName("invite") val invite: InviteDto,
    @SerialName("status") val status: InviteStatus,
)

/** Lifecycle state of an invite, derived from claim/expiry. */
@Serializable
enum class InviteStatus { PENDING, CLAIMED, EXPIRED }

/** What the public landing page shows for a code, before claiming. */
@Serializable
data class InvitePreview(
    @SerialName("displayName") val displayName: String,
    @SerialName("email") val email: String,
    @SerialName("invitedByName") val invitedByName: String,
    @SerialName("serverName") val serverName: String,
    @SerialName("valid") val valid: Boolean,
    @SerialName("invalidReason") val invalidReason: String? = null,
)
