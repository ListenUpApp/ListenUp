package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.dto.AccessMode as ContractAccessMode
import com.calypsan.listenup.api.dto.Library as ContractLibrary
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.client.data.remote.AdminApiContract
import com.calypsan.listenup.client.data.remote.AdminSettingsRpcFactory
import com.calypsan.listenup.client.data.remote.AdminUserRpcFactory
import com.calypsan.listenup.client.data.remote.BrowseFilesystemResponse
import com.calypsan.listenup.client.data.remote.CollectionRef
import com.calypsan.listenup.client.data.remote.InboxBookResponse
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
import com.calypsan.listenup.client.data.remote.LibraryResponse
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
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Implementation of AdminRepository using AdminApiContract for non-user operations,
 * [AdminUserRpcFactory] for user management, and [InviteRpcFactory] for invite management
 * (both routed through the Kotlin RPC server).
 *
 * All methods return [AppResult] — no exceptions are thrown. The [catching] helper wraps
 * every RPC call so that transport-level exceptions (e.g. [io.ktor.client.plugins.websocket.WebSocketException]
 * on a 401 WS handshake) are converted to [AppResult.Failure] rather than propagating as
 * unhandled exceptions. This mirrors [AuthRepositoryImpl.catching] and upholds the contract.
 *
 * @property adminApi API client for inbox/library operations
 * @property adminUserRpc RPC factory for user-management operations
 * @property adminSettingsRpc RPC factory for server-identity settings operations
 * @property inviteRpc RPC factory for invite-management operations
 * @property libraryAdminRpc RPC factory for library-admin operations (e.g. inbox toggle)
 * @property serverConfig source of the active server URL (used to reconstruct invite URLs)
 */
class AdminRepositoryImpl(
    private val adminApi: AdminApiContract,
    private val adminUserRpc: AdminUserRpcFactory,
    private val adminSettingsRpc: AdminSettingsRpcFactory,
    private val inviteRpc: InviteRpcFactory,
    private val libraryAdminRpc: LibraryAdminRpcFactory,
    private val serverConfig: ServerConfig,
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
    ): AppResult<AdminUserInfo> =
        catching("updateUser") {
            // firstName/lastName have no contract field — they must NOT be sent (displayName is
            // deferred to a future domain-realignment follow-up). The server applies
            // AdminUserPatch.permissions wholesale (canEdit + canShare) and the admin UI only
            // toggles canShare, so read the user first to preserve its current canEdit.
            val permissions =
                canShare?.let { share ->
                    when (val current = adminUserRpc.get().getUser(UserId(userId))) {
                        is AppResult.Success -> {
                            UserPermissions(canEdit = current.data.permissions.canEdit, canShare = share)
                        }

                        is AppResult.Failure -> {
                            return@catching current
                        }
                    }
                }
            val patch = AdminUserPatch(role = role?.let { UserRole.valueOf(it) }, permissions = permissions)
            adminUserRpc.get().updateUser(UserId(userId), patch).map { it.toAdminUserInfo() }
        }

    override suspend fun getRegistrationPolicy(): AppResult<Boolean> =
        catching("getRegistrationPolicy") {
            adminUserRpc.get().getRegistrationPolicy().map { it == RegistrationPolicy.OPEN }
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
        catching("getInvites") {
            val serverUrl = serverConfig.getActiveUrl()?.value.orEmpty()
            inviteRpc.adminService().listInvites().map { list -> list.map { it.toInviteInfo(serverUrl) } }
        }

    override suspend fun createInvite(
        name: String,
        email: String,
        role: String,
        expiresInDays: Int,
    ): AppResult<InviteInfo> =
        catching("createInvite") {
            val serverUrl = serverConfig.getActiveUrl()?.value.orEmpty()
            inviteRpc
                .adminService()
                .createInvite(
                    email = email,
                    displayName = name,
                    role = UserRole.valueOf(role),
                    expiresInDays = expiresInDays,
                ).map { it.toInviteInfo(serverUrl) }
        }

    override suspend fun deleteInvite(inviteId: String): AppResult<Unit> =
        catching("deleteInvite") { inviteRpc.adminService().revokeInvite(InviteId(inviteId)) }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVER SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun setOpenRegistration(enabled: Boolean): AppResult<Unit> =
        catching("setOpenRegistration") {
            adminUserRpc.get().setRegistrationPolicy(
                if (enabled) RegistrationPolicy.OPEN else RegistrationPolicy.CLOSED,
            )
        }

    override suspend fun getServerSettings(): AppResult<ServerSettings> =
        catching("getServerSettings") {
            adminSettingsRpc.get().getServerSettings().map { ServerSettings(it.serverName, it.remoteUrl) }
        }

    override suspend fun updateServerSettings(
        serverName: String?,
        remoteUrl: String?,
    ): AppResult<ServerSettings> =
        catching("updateServerSettings") {
            adminSettingsRpc
                .get()
                .updateServerSettings(AdminServerSettingsPatch(serverName = serverName, remoteUrl = remoteUrl))
                .map { ServerSettings(it.serverName, it.remoteUrl) }
        }

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

    override suspend fun setInboxEnabled(
        libraryId: String,
        enabled: Boolean,
    ): AppResult<Library> =
        catching("setInboxEnabled") {
            libraryAdminRpc.get().setInboxEnabled(LibraryId(libraryId), enabled).map { it.toDomain() }
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
 * Convert the contract [ContractLibrary] (returned by [com.calypsan.listenup.api.LibraryAdminService])
 * to the [Library] domain model.
 *
 * The contract DTO carries no `revision` (that lives on the member-synced
 * projection, not the admin aggregate), so it defaults to 0L — matching the
 * Go-REST [LibraryResponse.toDomain] mapping until the full admin RPC rewire lands.
 */
private fun ContractLibrary.toDomain(): Library =
    Library(
        id = id.value,
        name = name,
        metadataPrecedence = metadataPrecedence,
        accessMode =
            when (accessMode) {
                ContractAccessMode.SHARED -> AccessMode.OPEN
                ContractAccessMode.RESTRICTED, ContractAccessMode.PRIVATE -> AccessMode.RESTRICTED
            },
        createdByUserId = createdByUserId?.value,
        createdAt = createdAt,
        revision = 0L,
        inboxEnabled = inboxEnabled,
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
