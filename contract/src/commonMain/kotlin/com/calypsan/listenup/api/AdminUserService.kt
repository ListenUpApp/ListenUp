package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * Admin-only user management. Every method requires ROOT/ADMIN; non-admins
 * receive [com.calypsan.listenup.api.error.AuthError.Forbidden].
 *
 * Covers the full administrative lifecycle of an account — listing the roster,
 * reviewing the pending-approval queue, inspecting and searching users, applying
 * partial updates via [AdminUserPatch], and removing accounts — plus the
 * instance-wide [RegistrationPolicy] that governs how new accounts are admitted.
 *
 * REST mirrors are defined in
 * [com.calypsan.listenup.api.resources.AdminUserResources] and
 * [com.calypsan.listenup.api.resources.RegistrationPolicyResource].
 */
@Rpc
interface AdminUserService {
    /** Returns every user on the instance, regardless of status. */
    suspend fun listUsers(): AppResult<List<User>>

    /** Returns only users awaiting an approval decision (PENDING_APPROVAL). */
    suspend fun listPendingUsers(): AppResult<List<User>>

    /** Returns the user identified by [id]. Fails when no such user exists. */
    suspend fun getUser(id: UserId): AppResult<User>

    /** Returns users whose display name or email matches [query]. */
    suspend fun searchUsers(query: String): AppResult<List<User>>

    /**
     * Applies the non-null fields of [patch] to the user identified by [id] and
     * returns the updated [User]. ROOT cannot be demoted. Fails when no such
     * user exists or when the change is disallowed.
     */
    suspend fun updateUser(
        id: UserId,
        patch: AdminUserPatch,
    ): AppResult<User>

    /**
     * Deletes the user identified by [id]. ROOT cannot be deleted. Fails when no
     * such user exists or when the change is disallowed.
     */
    suspend fun deleteUser(id: UserId): AppResult<Unit>

    /** Returns the instance-wide registration policy. */
    suspend fun getRegistrationPolicy(): AppResult<RegistrationPolicy>

    /** Replaces the instance-wide registration policy with [policy]. */
    suspend fun setRegistrationPolicy(policy: RegistrationPolicy): AppResult<Unit>
}
