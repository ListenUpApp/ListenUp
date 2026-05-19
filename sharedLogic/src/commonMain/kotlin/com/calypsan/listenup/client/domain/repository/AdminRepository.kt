package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InboxBook
import com.calypsan.listenup.client.domain.model.InboxReleaseResult
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
     * Enable or disable open registration.
     *
     * When enabled, new users can register without an invite.
     *
     * @param enabled True to enable open registration
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun setOpenRegistration(enabled: Boolean): AppResult<Unit>

    /**
     * Get server settings.
     *
     * @return [AppResult] carrying current server settings including inbox status, or a failure.
     */
    suspend fun getServerSettings(): AppResult<ServerSettings>

    /**
     * Update instance settings (remote URL, name).
     *
     * @return [AppResult] carrying the updated remote URL (may be null), or a failure.
     */
    suspend fun updateInstanceRemoteUrl(remoteUrl: String): AppResult<String?>

    /**
     * Update server-wide settings.
     *
     * @param serverName New server display name (null to keep unchanged)
     * @param inboxEnabled New inbox workflow state (null to keep unchanged)
     * @return [AppResult] carrying updated server settings, or a failure.
     */
    suspend fun updateServerSettings(
        serverName: String? = null,
        inboxEnabled: Boolean? = null,
    ): AppResult<ServerSettings>

    // ═══════════════════════════════════════════════════════════════════════
    // INBOX MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get all books in the inbox.
     *
     * @return [AppResult] carrying list of inbox books awaiting review, or a failure.
     */
    suspend fun getInboxBooks(): AppResult<List<InboxBook>>

    /**
     * Release books from inbox.
     *
     * Released books become visible to users (either publicly or
     * in their staged collections).
     *
     * @param bookIds List of book IDs to release
     * @return [AppResult] carrying release result with counts, or a failure.
     */
    suspend fun releaseBooks(bookIds: List<String>): AppResult<InboxReleaseResult>

    /**
     * Stage a collection for an inbox book.
     *
     * When the book is released, it will be added to this collection.
     *
     * @param bookId The inbox book ID
     * @param collectionId The collection to stage
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun stageCollection(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit>

    /**
     * Remove a staged collection from an inbox book.
     *
     * @param bookId The inbox book ID
     * @param collectionId The collection to unstage
     * @return [AppResult] carrying [Unit] on success, or a failure.
     */
    suspend fun unstageCollection(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit>

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
     * Update library settings.
     *
     * @param libraryId The library ID to update
     * @param name New library name (null to keep unchanged)
     * @param skipInbox New inbox skip setting (null to keep unchanged)
     * @param accessMode New access mode (null to keep unchanged)
     * @return [AppResult] carrying the updated library, or a failure.
     */
    suspend fun updateLibrary(
        libraryId: String,
        name: String? = null,
        skipInbox: Boolean? = null,
        accessMode: AccessMode? = null,
    ): AppResult<Library>

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
     * Remove a scan path from a library.
     *
     * @param libraryId The library ID
     * @param path The scan path to remove
     * @return [AppResult] carrying the updated library, or a failure.
     */
    suspend fun removeScanPath(
        libraryId: String,
        path: String,
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
