package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.data.remote.AdminApiContract
import com.calypsan.listenup.client.data.remote.AdminUserRpcFactory
import com.calypsan.listenup.client.data.remote.BrowseFilesystemResponse
import com.calypsan.listenup.client.data.remote.AdminInvite
import com.calypsan.listenup.client.data.remote.CollectionRef
import com.calypsan.listenup.client.data.remote.CreateInviteRequest
import com.calypsan.listenup.client.data.remote.InboxBookResponse
import com.calypsan.listenup.client.data.remote.LibraryResponse
import com.calypsan.listenup.client.data.remote.ServerSettingsRequest
import com.calypsan.listenup.client.data.remote.UpdateInstanceRequest
import com.calypsan.listenup.client.data.remote.ServerSettingsResponse
import com.calypsan.listenup.client.data.remote.UpdateLibraryRequest
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InboxBook
import com.calypsan.listenup.client.domain.model.InboxReleaseResult
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.model.StagedCollection
import com.calypsan.listenup.client.domain.repository.AdminRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Implementation of AdminRepository using AdminApiContract for non-user operations
 * and [AdminUserRpcFactory] for user management (routed through the Kotlin RPC server).
 *
 * All methods return [AppResult] — no exceptions are thrown. The [catching] helper wraps
 * every RPC call so that transport-level exceptions (e.g. [io.ktor.client.plugins.websocket.WebSocketException]
 * on a 401 WS handshake) are converted to [AppResult.Failure] rather than propagating as
 * unhandled exceptions. This mirrors [AuthRepositoryImpl.catching] and upholds the contract.
 *
 * @property adminApi API client for invite/settings/inbox/library operations
 * @property adminUserRpc RPC factory for user-management operations
 */
class AdminRepositoryImpl(
    private val adminApi: AdminApiContract,
    private val adminUserRpc: AdminUserRpcFactory,
) : AdminRepository {
    // ═══════════════════════════════════════════════════════════════════════
    // USER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getUsers(): AppResult<List<AdminUserInfo>> =
        catching("getUsers") {
            adminUserRpc.get().listUsers().map { users -> users.map { it.toAdminUserInfo() } }
        }

    override suspend fun getPendingUsers(): AppResult<List<AdminUserInfo>> =
        catching("getPendingUsers") {
            adminUserRpc.get().listPendingUsers().map { users -> users.map { it.toAdminUserInfo() } }
        }

    override suspend fun approveUser(userId: String): AppResult<AdminUserInfo> =
        catching("approveUser") {
            adminUserRpc
                .get()
                .decidePendingRegistration(PendingRegistrationDecision(UserId(userId), approved = true))
                .flatMap { adminUserRpc.get().getUser(UserId(userId)) }
                .map { it.toAdminUserInfo() }
        }

    override suspend fun denyUser(userId: String): AppResult<Unit> =
        catching("denyUser") {
            adminUserRpc
                .get()
                .decidePendingRegistration(PendingRegistrationDecision(UserId(userId), approved = false))
                .map { }
        }

    override suspend fun deleteUser(userId: String): AppResult<Unit> =
        catching("deleteUser") { adminUserRpc.get().deleteUser(UserId(userId)) }

    override suspend fun getUser(userId: String): AppResult<AdminUserInfo> =
        catching("getUser") {
            adminUserRpc.get().getUser(UserId(userId)).map { it.toAdminUserInfo() }
        }

    override suspend fun updateUser(
        userId: String,
        firstName: String?,
        lastName: String?,
        role: String?,
        canShare: Boolean?,
    ): AppResult<AdminUserInfo> {
        // firstName/lastName have no contract field — they must NOT be sent to the server.
        // displayName is deferred to a future domain-realignment follow-up.
        val patch =
            AdminUserPatch(
                role = role?.let { UserRole.valueOf(it) },
                permissions = canShare?.let { UserPermissions(canShare = it) },
            )
        return catching("updateUser") {
            adminUserRpc.get().updateUser(UserId(userId), patch).map { it.toAdminUserInfo() }
        }
    }

    /**
     * Catches transport-level exceptions from RPC calls and converts them to
     * [AppResult.Failure], preserving the [AppResult] contract. [CancellationException]
     * is always re-thrown per kotlinx.coroutines convention.
     *
     * This is required because [AdminUserRpcFactory.get] opens a WebSocket connection
     * on first use; if authentication fails (HTTP 401 during the WS upgrade), the RPC
     * library throws [io.ktor.client.plugins.websocket.WebSocketException] rather than
     * returning an error response. Without this boundary the exception escapes into the
     * caller's coroutine as an unhandled exception.
     */
    private suspend inline fun <T> catching(
        op: String,
        block: () -> AppResult<T>,
    ): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "admin user RPC $op failed at the transport boundary" }
            AppResult.Failure(InternalError())
        }

    // ═══════════════════════════════════════════════════════════════════════
    // INVITE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getInvites(): AppResult<List<InviteInfo>> =
        adminApi.getInvites().map { invites -> invites.map { it.toDomain() } }

    override suspend fun createInvite(
        name: String,
        email: String,
        role: String,
        expiresInDays: Int,
    ): AppResult<InviteInfo> {
        val request =
            CreateInviteRequest(
                name = name,
                email = email,
                role = role,
                expiresInDays = expiresInDays,
            )
        return adminApi.createInvite(request).map { it.toDomain() }
    }

    override suspend fun deleteInvite(inviteId: String): AppResult<Unit> = adminApi.deleteInvite(inviteId)

    // ═══════════════════════════════════════════════════════════════════════
    // SERVER SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun setOpenRegistration(enabled: Boolean): AppResult<Unit> = adminApi.setOpenRegistration(enabled)

    override suspend fun updateInstanceRemoteUrl(remoteUrl: String): AppResult<String?> =
        adminApi.updateInstance(UpdateInstanceRequest(remoteUrl = remoteUrl)).map { it.remoteUrl }

    override suspend fun getServerSettings(): AppResult<ServerSettings> =
        adminApi.getServerSettings().map { it.toDomain() }

    override suspend fun updateServerSettings(
        serverName: String?,
        inboxEnabled: Boolean?,
    ): AppResult<ServerSettings> =
        adminApi
            .updateServerSettings(
                ServerSettingsRequest(serverName = serverName, inboxEnabled = inboxEnabled),
            ).map { it.toDomain() }

    // ═══════════════════════════════════════════════════════════════════════
    // INBOX MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getInboxBooks(): AppResult<List<InboxBook>> =
        adminApi.listInboxBooks().map { it.books.map { book -> book.toDomain() } }

    override suspend fun releaseBooks(bookIds: List<String>): AppResult<InboxReleaseResult> =
        adminApi.releaseBooks(bookIds).map { response ->
            InboxReleaseResult(
                released = response.released,
                publicCount = response.public,
                toCollections = response.toCollections,
            )
        }

    override suspend fun stageCollection(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit> = adminApi.stageCollection(bookId, collectionId)

    override suspend fun unstageCollection(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit> = adminApi.unstageCollection(bookId, collectionId)

    // ═══════════════════════════════════════════════════════════════════════
    // LIBRARY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getLibraries(): AppResult<List<Library>> =
        adminApi.getLibraries().map { libraries -> libraries.map { it.toDomain() } }

    override suspend fun getLibrary(libraryId: String): AppResult<Library> =
        adminApi.getLibrary(libraryId).map { it.toDomain() }

    override suspend fun updateLibrary(
        libraryId: String,
        name: String?,
        skipInbox: Boolean?,
        accessMode: AccessMode?,
    ): AppResult<Library> {
        val request =
            UpdateLibraryRequest(
                name = name,
                skipInbox = skipInbox,
                accessMode = accessMode?.toApiString(),
            )
        return adminApi.updateLibrary(libraryId, request).map { it.toDomain() }
    }

    override suspend fun addScanPath(
        libraryId: String,
        path: String,
    ): AppResult<Library> = adminApi.addScanPath(libraryId, path).map { it.toDomain() }

    override suspend fun removeScanPath(
        libraryId: String,
        path: String,
    ): AppResult<Library> = adminApi.removeScanPath(libraryId, path).map { it.toDomain() }

    override suspend fun triggerScan(libraryId: String): AppResult<Unit> = adminApi.triggerScan(libraryId)

    override suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse> =
        adminApi.browseFilesystem(path)
}

// ═══════════════════════════════════════════════════════════════════════════
// CONVERSION FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Convert AdminInvite API model to InviteInfo domain model.
 */
private fun AdminInvite.toDomain(): InviteInfo =
    InviteInfo(
        id = id,
        code = code,
        name = name,
        email = email,
        role = role,
        expiresAt = expiresAt,
        claimedAt = claimedAt,
        url = url,
        createdAt = createdAt,
    )

/**
 * Convert ServerSettingsResponse API model to ServerSettings domain model.
 */
private fun ServerSettingsResponse.toDomain(): ServerSettings =
    ServerSettings(
        serverName = serverName,
        inboxEnabled = inboxEnabled,
        inboxCount = inboxCount,
    )

/**
 * Convert InboxBookResponse API model to InboxBook domain model.
 */
private fun InboxBookResponse.toDomain(): InboxBook =
    InboxBook(
        id = id,
        title = title,
        author = author,
        coverUrl = coverUrl,
        duration = duration,
        stagedCollectionIds = stagedCollectionIds,
        stagedCollections = stagedCollections.map { it.toDomain() },
        scannedAt = scannedAt,
    )

/**
 * Convert CollectionRef API model to StagedCollection domain model.
 */
private fun CollectionRef.toDomain(): StagedCollection =
    StagedCollection(
        id = id,
        name = name,
    )

/**
 * Convert LibraryResponse API model to Library domain model.
 *
 * Legacy Go REST response: `metadataPrecedence`, `createdByUserId`, and `revision`
 * are not present on the wire. Defaults apply until the Kotlin server RPC
 * path replaces this code in the LibraryAdminService rewire (Task 25+).
 */
private fun LibraryResponse.toDomain(): Library =
    Library(
        id = id,
        name = name,
        metadataPrecedence = "embedded,abs",
        accessMode = AccessMode.fromString(accessMode),
        createdByUserId = null,
        createdAt = 0L,
        revision = 0L,
    )
