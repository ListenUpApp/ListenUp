@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.data.remote.BrowseFilesystemResponse
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.ServerSettings
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for admin operations.
 *
 * Provides access to user management, invite management, and server settings.
 * Only accessible to users with admin privileges.
 *
 * All methods return [AppResult] — callers handle [AppResult.Success] and
 * [AppResult.Failure] directly without catching exceptions.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
@Suppress("TooManyFunctions")
interface AdminRepository {
    // ═══════════════════════════════════════════════════════════════════════
    // USER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get all approved users.
     *
     * @return [AppResult] carrying list of all approved users, or a failure.
     */
    suspend fun getUsers(): AppResult<List<AdminUserInfo>>

    /**
     * Get all users awaiting approval.
     *
     * @return [AppResult] carrying list of pending users, or a failure.
     */
    suspend fun getPendingUsers(): AppResult<List<AdminUserInfo>>

    /**
     * Observe the live admin user roster from the Room-backed `admin_user_roster` sync
     * domain — the offline-first, always-current projection of ACTIVE/PENDING_APPROVAL
     * users backing the admin Users and pending-approval screens.
     *
     * @return [Flow] emitting the full roster whenever it changes.
     */
    fun observeRoster(): Flow<List<AdminUserInfo>>

    /**
     * Approve a pending user registration.
     *
     * @param userId The user ID to approve
     * @return [AppResult] carrying the approved user info, or a failure.
     */
    suspend fun approveUser(userId: String): AppResult<AdminUserInfo>

    /**
     * Deny a pending user registration.
     *
     * @param userId The user ID to deny
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun denyUser(userId: String): AppResult<Unit>

    /**
     * Delete an existing user.
     *
     * @param userId The user ID to delete
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun deleteUser(userId: String): AppResult<Unit>

    /**
     * Get a single user by ID.
     *
     * @param userId The user ID to fetch
     * @return [AppResult] carrying the user info, or a failure.
     */
    suspend fun getUser(userId: String): AppResult<AdminUserInfo>

    /**
     * Update a user's details and permissions.
     *
     * @param userId The user ID to update
     * @param firstName New first name (null to keep unchanged)
     * @param lastName New last name (null to keep unchanged)
     * @param role New role (null to keep unchanged)
     * @param canShare New share permission (null to keep unchanged)
     * @return [AppResult] carrying the updated user info, or a failure.
     */
    suspend fun updateUser(
        userId: String,
        firstName: String? = null,
        lastName: String? = null,
        role: String? = null,
        canShare: Boolean? = null,
    ): AppResult<AdminUserInfo>

    // ═══════════════════════════════════════════════════════════════════════
    // INVITE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get all invites (both pending and claimed).
     *
     * @return [AppResult] carrying list of all invites, or a failure.
     */
    suspend fun getInvites(): AppResult<List<InviteInfo>>

    /**
     * Create a new invite code.
     *
     * The invitee names their own account when they claim the invite, so no display name is
     * collected here — the admin only supplies the email the invite is bound to.
     *
     * @param email Email to restrict the invite to
     * @param role Role to assign to users who use this invite
     * @param expiresInDays Number of days until the invite expires
     * @return [AppResult] carrying the created invite, or a failure.
     */
    suspend fun createInvite(
        email: String,
        role: String = "member",
        expiresInDays: Int = 7,
    ): AppResult<InviteInfo>

    /**
     * Delete/revoke an invite.
     *
     * @param inviteId The invite ID to delete
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun deleteInvite(inviteId: String): AppResult<Unit>

    // ═══════════════════════════════════════════════════════════════════════
    // SERVER SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get the current [RegistrationPolicy] — the full three-state value
     * (`OPEN` / `APPROVAL_QUEUE` / `CLOSED`), not a lossy boolean. The admin UI
     * needs all three states to render and round-trip the control correctly.
     *
     * @return [AppResult] carrying the current [RegistrationPolicy], or a failure.
     */
    suspend fun getRegistrationPolicy(): AppResult<RegistrationPolicy>

    /**
     * Set the registration policy.
     *
     * @param policy the [RegistrationPolicy] to apply
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun setRegistrationPolicy(policy: RegistrationPolicy): AppResult<Unit>

    /** Current server-identity settings (name + remote URL). */
    suspend fun getServerSettings(): AppResult<ServerSettings>

    /** Patch server-identity settings (null = unchanged; remoteUrl "" clears). */
    suspend fun updateServerSettings(
        serverName: String? = null,
        remoteUrl: String? = null,
        inboxEnabled: Boolean? = null,
        pushNotificationsEnabled: Boolean? = null,
    ): AppResult<ServerSettings>

    // ═══════════════════════════════════════════════════════════════════════
    // LIBRARY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get THE library (single-library model).
     *
     * @return [AppResult] carrying the library, or a failure.
     */
    suspend fun getLibrary(): AppResult<Library>

    /**
     * Add a scan path to THE library.
     *
     * @param path Absolute filesystem path to add
     * @return [AppResult] carrying the updated library, or a failure.
     */
    suspend fun addScanPath(path: String): AppResult<Library>

    /**
     * Remove a folder from THE library by its folder id, cascade-deleting its books.
     *
     * @param folderId The folder id to remove.
     * @return [AppResult] carrying the updated library, or a failure.
     */
    suspend fun removeFolder(folderId: String): AppResult<Library>

    /**
     * Trigger a manual rescan of THE library.
     *
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun triggerScan(): AppResult<Unit>

    /**
     * Browse the server filesystem.
     *
     * @param path Directory path to browse
     * @return [AppResult] carrying directory listing, or a failure.
     */
    suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse>
}
