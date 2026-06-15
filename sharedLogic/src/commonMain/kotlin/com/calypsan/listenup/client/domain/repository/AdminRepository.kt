package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.data.remote.BrowseFilesystemResponse
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.ServerSettings

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
     * @param name Display name for the invite
     * @param email Email to restrict the invite to
     * @param role Role to assign to users who use this invite
     * @param expiresInDays Number of days until the invite expires
     * @return [AppResult] carrying the created invite, or a failure.
     */
    suspend fun createInvite(
        name: String,
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
     * Get the current registration policy as a simple open/closed boolean.
     *
     * Returns `true` when registration policy is [com.calypsan.listenup.api.dto.auth.RegistrationPolicy.OPEN],
     * `false` for all other policies (approval queue or closed). Callers do not need to
     * depend on the contract enum — the VM needs only the boolean to drive the UI toggle.
     *
     * @return [AppResult] carrying `true` if registration is open, `false` otherwise, or a failure.
     */
    suspend fun getRegistrationPolicy(): AppResult<Boolean>

    /**
     * Enable or disable open registration.
     *
     * When enabled, new users can register without an invite.
     *
     * @param enabled True to enable open registration
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun setOpenRegistration(enabled: Boolean): AppResult<Unit>

    /** Current server-identity settings (name + remote URL). */
    suspend fun getServerSettings(): AppResult<ServerSettings>

    /** Patch server-identity settings (null = unchanged; remoteUrl "" clears). */
    suspend fun updateServerSettings(
        serverName: String? = null,
        remoteUrl: String? = null,
        inboxEnabled: Boolean? = null,
    ): AppResult<ServerSettings>

    // ═══════════════════════════════════════════════════════════════════════
    // LIBRARY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get all libraries.
     *
     * @return [AppResult] carrying list of all libraries, or a failure.
     */
    suspend fun getLibraries(): AppResult<List<Library>>

    /**
     * Get a specific library.
     *
     * @param libraryId The library ID
     * @return [AppResult] carrying the library, or a failure.
     */
    suspend fun getLibrary(libraryId: String): AppResult<Library>

    /**
     * Add a scan path to a library.
     *
     * @param libraryId The library ID
     * @param path Absolute filesystem path to add
     * @return [AppResult] carrying the updated library, or a failure.
     */
    suspend fun addScanPath(
        libraryId: String,
        path: String,
    ): AppResult<Library>

    /**
     * Remove a folder from a library by its folder id, cascade-deleting its books.
     *
     * @param libraryId The library the folder belongs to (used to re-fetch the updated aggregate).
     * @param folderId The folder id to remove.
     * @return [AppResult] carrying the updated library, or a failure.
     */
    suspend fun removeFolder(
        libraryId: String,
        folderId: String,
    ): AppResult<Library>

    /**
     * Trigger a manual library rescan.
     *
     * @param libraryId The library ID
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun triggerScan(libraryId: String): AppResult<Unit>

    /**
     * Browse the server filesystem.
     *
     * @param path Directory path to browse
     * @return [AppResult] carrying directory listing, or a failure.
     */
    suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse>
}
