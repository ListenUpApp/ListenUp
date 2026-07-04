package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire payload for the admin-only `admin_user_roster` sync domain — one row per ACTIVE or
 * PENDING_APPROVAL user, carrying exactly the fields the admin Users/pending lists render.
 * Delivery is gated to admins on the firehose (see SyncRoutes); non-admins never receive it.
 */
@Serializable
@SerialName("AdminUserRosterSyncPayload")
data class AdminUserRosterSyncPayload(
    override val id: String,
    val email: String,
    val displayName: String,
    val role: String,
    val status: String,
    val canShare: Boolean,
    val accountCreatedAt: Long,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : SyncPayload
