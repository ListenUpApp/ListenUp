package com.calypsan.listenup.api.dto.invite

import com.calypsan.listenup.api.dto.auth.UserRole
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A full invite as returned to an admin on creation/listing. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InviteDto(
    @SerialName("id") val id: InviteId,
    @SerialName("code") val code: String,
    @SerialName("email") val email: String,
    @SerialName("displayName") val displayName: String,
    // An older app reads a role it has never heard of as MEMBER; display-only here. Always encoded so
    // an app built before this default existed still finds the key.
    @SerialName("role") @EncodeDefault(EncodeDefault.Mode.ALWAYS) val role: UserRole = UserRole.MEMBER,
    @SerialName("createdBy") val createdBy: String,
    @SerialName("expiresAt") val expiresAt: Long,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("claimedAt") val claimedAt: Long? = null,
    @SerialName("claimedBy") val claimedBy: String? = null,
)

/**
 * Admin-list row: an invite plus a derived [status]. A status this build has never heard of reads
 * as [InviteStatus.UNKNOWN], so one row turns vague rather than the whole list failing to decode.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InviteSummary(
    @SerialName("invite") val invite: InviteDto,
    @SerialName("status") @EncodeDefault(EncodeDefault.Mode.ALWAYS) val status: InviteStatus = InviteStatus.UNKNOWN,
)

/**
 * Lifecycle state of an invite, derived from claim/expiry. [UNKNOWN] is what an older app reads a
 * state a newer server added as; the server never derives it.
 */
@Serializable
enum class InviteStatus { PENDING, CLAIMED, EXPIRED, UNKNOWN }

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
