package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.dto.auth.UserPermissions
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire payload for the admin-only `admin_user_roster` sync domain — one row per ACTIVE or
 * PENDING_APPROVAL user, carrying exactly the fields the admin Users/pending lists render.
 * Delivery is gated to admins on the firehose (see SyncRoutes); non-admins never receive it.
 *
 * @property canShare **Deprecated compat shim — always `true`, ignored by every client.** The
 *   "Can share" permission was removed; this field survives only because admin apps built before
 *   the removal decode it as required, and a payload missing it would freeze their roster sync.
 *   It is always encoded (`contractJson` skips defaults otherwise). Remove it in a later release,
 *   once un-updated admin clients have aged out.
 * @property canEdit **Deprecated — read [permissions].** Kept, and still written, for admin apps built
 *   before the permission split, which read this flat field. Remove with [canShare].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AdminUserRosterSyncPayload")
data class AdminUserRosterSyncPayload(
    override val id: String,
    val email: String,
    val displayName: String,
    val role: String,
    val status: String,
    @Deprecated(
        "Compat shim for un-updated admin apps; always true, read by nothing. Remove in a later release.",
        level = DeprecationLevel.WARNING,
    )
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val canShare: Boolean = true,
    val canEdit: Boolean = true,
    /**
     * Every permission flag, nested so a new flag never adds a roster field. Null from a server older
     * than the permission split; a client then reads [canEdit] and leaves every other flag off.
     */
    @SerialName("permissions") val permissions: UserPermissions? = null,
    val accountCreatedAt: Long,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : SyncPayload
