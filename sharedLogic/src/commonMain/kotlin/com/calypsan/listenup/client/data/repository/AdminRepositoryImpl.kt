package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.LibraryAdminService
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
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.client.data.local.db.AdminUserRosterDao
import com.calypsan.listenup.client.data.remote.BrowseFilesystemResponse
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.LibraryFolderRef
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Implementation of AdminRepository backed entirely by Kotlin RPC services.
 *
 * All methods return [AppResult] — no exceptions are thrown. The three admin services dispatch
 * through their [RpcChannel], which folds transport faults into typed [AppResult.Failure]s, re-raises
 * cancellation, and self-heals its own connection. The invite surface still rides the
 * [InviteRpcFactory] seam, so its calls are wrapped by the [catching] helper that converts a
 * transport-level exception into an [AppResult.Failure] and drops the RPC caches.
 *
 * @property adminUserChannel Dispatches the [com.calypsan.listenup.api.AdminUserService] user-management RPC.
 * @property adminSettingsChannel Dispatches the [com.calypsan.listenup.api.AdminSettingsService] server-identity RPC.
 * @property inviteRpc RPC factory for invite-management operations
 * @property libraryAdminChannel Dispatches the [com.calypsan.listenup.api.LibraryAdminService] library-admin RPC.
 * @property serverConfig source of the active server URL (used to reconstruct invite URLs)
 * @property adminUserRosterDao Room DAO for the synced `admin_user_roster` sync domain, backing
 *   [observeRoster]
 */
internal class AdminRepositoryImpl(
    private val adminUserChannel: RpcChannel<AdminUserService>,
    private val adminSettingsChannel: RpcChannel<AdminSettingsService>,
    private val inviteRpc: InviteRpcFactory,
    private val libraryAdminChannel: RpcChannel<LibraryAdminService>,
    private val serverConfig: ServerConfig,
    private val adminUserRosterDao: AdminUserRosterDao,
    private val rpcCacheInvalidator: RpcCacheInvalidator =
        object : RpcCacheInvalidator {
            override suspend fun invalidateAll() = Unit

            override suspend fun invalidateRequestCaches() = Unit
        },
) : AdminRepository {
    // ═══════════════════════════════════════════════════════════════════════
    // USER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getUsers(): AppResult<List<AdminUserInfo>> =
        adminUserChannel.call { it.listUsers() }.map { users -> users.map { it.toAdminUserInfo() } }

    override suspend fun getPendingUsers(): AppResult<List<AdminUserInfo>> =
        adminUserChannel.call { it.listPendingUsers() }.map { users -> users.map { it.toAdminUserInfo() } }

    override fun observeRoster(): Flow<List<AdminUserInfo>> =
        adminUserRosterDao.observeAll().map { rows -> rows.map { it.toAdminUserInfo() } }

    override suspend fun approveUser(userId: String): AppResult<AdminUserInfo> =
        adminUserChannel.call { service ->
            service
                .decidePendingRegistration(PendingRegistrationDecision(UserId(userId), approved = true))
                .flatMap { service.getUser(UserId(userId)) }
                .map { it.toAdminUserInfo() }
        }

    override suspend fun denyUser(userId: String): AppResult<Unit> =
        adminUserChannel
            .call { it.decidePendingRegistration(PendingRegistrationDecision(UserId(userId), approved = false)) }
            .map { }

    override suspend fun deleteUser(userId: String): AppResult<Unit> =
        adminUserChannel.call { it.deleteUser(UserId(userId)) }

    override suspend fun getUser(userId: String): AppResult<AdminUserInfo> =
        adminUserChannel.call { it.getUser(UserId(userId)) }.map { it.toAdminUserInfo() }

    override suspend fun updateUser(
        userId: String,
        firstName: String?,
        lastName: String?,
        role: String?,
        canShare: Boolean?,
    ): AppResult<AdminUserInfo> =
        adminUserChannel.call { service ->
            // firstName/lastName have no contract field — they must NOT be sent (displayName is
            // deferred to a future domain-realignment follow-up). The server applies
            // AdminUserPatch.permissions wholesale (canEdit + canShare) and the admin UI only
            // toggles canShare, so read the user first to preserve its current canEdit.
            val permissions =
                canShare?.let { share ->
                    when (val current = service.getUser(UserId(userId))) {
                        is AppResult.Success -> {
                            UserPermissions(canEdit = current.data.permissions.canEdit, canShare = share)
                        }

                        is AppResult.Failure -> {
                            return@call current
                        }
                    }
                }
            val patch = AdminUserPatch(role = role?.let { UserRole.valueOf(it) }, permissions = permissions)
            service.updateUser(UserId(userId), patch).map { it.toAdminUserInfo() }
        }

    override suspend fun getRegistrationPolicy(): AppResult<RegistrationPolicy> =
        adminUserChannel.call { it.getRegistrationPolicy() }

    /**
     * Catches transport-level exceptions from the invite RPC calls and converts them to
     * [AppResult.Failure], preserving the [AppResult] contract. [CancellationException]
     * is always re-thrown per kotlinx.coroutines convention.
     *
     * This is required because [InviteRpcFactory.adminService] opens a WebSocket connection
     * on first use; if authentication fails (HTTP 401 during the WS upgrade), the RPC
     * library throws [io.ktor.client.plugins.websocket.WebSocketException] rather than
     * returning an error response. Without this boundary the exception escapes into the
     * caller's coroutine as an unhandled exception. The migrated admin services get the same
     * guarantee from their [RpcChannel] instead.
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
            // An exception reaching this catch is a transport-level failure (the data layer returns
            // typed failures rather than raising them). The cached kotlinx.rpc proxy is likely bound
            // to a dead/cancelled RpcClient after a reconnect to the same server — so drop the caches
            // now and the next call (or a screen re-entry) rebinds to the live connection instead of
            // stranding the user until app restart. No auto-retry: this path also wraps
            // non-idempotent writes, so re-firing the same call could double-apply.
            logger.warn(e) { "admin RPC $op failed at the transport boundary; invalidating RPC caches" }
            rpcCacheInvalidator.invalidateAll()
            AppResult.Failure(InternalError())
        }

    // ═══════════════════════════════════════════════════════════════════════
    // INVITE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getInvites(): AppResult<List<InviteInfo>> =
        catching("getInvites") {
            val serverUrl = serverConfig.getActiveUrl()?.value.orEmpty()
            val remoteUrl = inviteRemoteUrl(serverUrl)
            inviteRpc.adminService().listInvites().map { list -> list.map { it.toInviteInfo(serverUrl, remoteUrl) } }
        }

    override suspend fun createInvite(
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
                    // The admin no longer names the invitee — they choose a display name when they
                    // claim. The email's local part is a non-blank placeholder that satisfies the
                    // server's display_name invariant until the claimer overrides it.
                    displayName = email.substringBefore('@'),
                    role = role.toInviteRole(),
                    expiresInDays = expiresInDays,
                ).map { it.toInviteInfo(serverUrl, inviteRemoteUrl(serverUrl)) }
        }

    /**
     * The operator-set remote (WAN) URL to embed alongside the local [serverUrl] in an invite link,
     * so an invitee off the local network can still connect. `null` when unset or identical to the
     * local URL (no point carrying a duplicate). The claim flow tries local first, then this.
     */
    private suspend fun inviteRemoteUrl(serverUrl: String): String? =
        serverConfig.getRemoteUrl()?.value?.takeIf { it.isNotBlank() && it != serverUrl }

    override suspend fun deleteInvite(inviteId: String): AppResult<Unit> =
        catching("deleteInvite") { inviteRpc.adminService().revokeInvite(InviteId(inviteId)) }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVER SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun setRegistrationPolicy(policy: RegistrationPolicy): AppResult<Unit> =
        adminUserChannel.call { it.setRegistrationPolicy(policy) }

    override suspend fun getServerSettings(): AppResult<ServerSettings> =
        adminSettingsChannel.call { it.getServerSettings() }.map {
            ServerSettings(
                it.serverName,
                it.remoteUrl,
                it.inboxEnabled,
            )
        }

    override suspend fun updateServerSettings(
        serverName: String?,
        remoteUrl: String?,
        inboxEnabled: Boolean?,
    ): AppResult<ServerSettings> =
        adminSettingsChannel
            .call {
                it.updateServerSettings(
                    AdminServerSettingsPatch(
                        serverName = serverName,
                        remoteUrl = remoteUrl,
                        inboxEnabled = inboxEnabled,
                    ),
                )
            }.map { ServerSettings(it.serverName, it.remoteUrl, it.inboxEnabled) }

    // ═══════════════════════════════════════════════════════════════════════
    // LIBRARY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun getLibrary(): AppResult<Library> =
        libraryAdminChannel.call { it.getLibrary() }.map { it.toDomain() }

    override suspend fun addScanPath(path: String): AppResult<Library> =
        libraryAdminChannel.call { service ->
            service.addFolder(path).flatMap { folder ->
                // Scan JUST the folder we added — a full library rescan takes minutes on a large
                // library. Best-effort: the folder is already registered server-side, so a
                // scan-trigger hiccup must not fail the add (that would strand the admin with a
                // folder that looks like it failed). Log and continue; the library screen still
                // offers a manual full rescan as the fallback.
                val scan = service.scanFolder(folder.id)
                if (scan is AppResult.Failure) {
                    logger.warn { "Folder ${folder.id.value} added but its scan trigger failed: ${scan.error.code}" }
                }
                getLibrary()
            }
        }

    override suspend fun removeFolder(folderId: String): AppResult<Library> =
        libraryAdminChannel.call { it.removeFolder(FolderId(folderId)) }.flatMap { getLibrary() }

    override suspend fun triggerScan(): AppResult<Unit> = libraryAdminChannel.call { it.scanLibrary() }

    override suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse> =
        libraryAdminChannel.call { it.browseFilesystem(path) }.map { entries ->
            val isRoot = path == "/"
            val parent =
                if (isRoot) {
                    null
                } else {
                    val lastSlash = path.lastIndexOf('/')
                    if (lastSlash <= 0) "/" else path.substring(0, lastSlash)
                }
            BrowseFilesystemResponse(
                path = path,
                parent = parent,
                entries = entries.map { DirectoryEntryResponse(name = it.name, path = it.path) },
                isRoot = isRoot,
            )
        }
}

// ═══════════════════════════════════════════════════════════════════════════
// CONVERSION FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Maps the create-invite form's role token to the wire [UserRole].
 *
 * The form emits lowercase strings (`"member"`/`"admin"`); a case-insensitive match avoids the
 * `IllegalArgumentException` that `UserRole.valueOf("member")` raised (it requires the exact enum
 * names). Anything that isn't an explicit admin grant falls back to the least-privileged MEMBER.
 */
private fun String.toInviteRole(): UserRole =
    if (trim().equals("admin", ignoreCase = true)) UserRole.ADMIN else UserRole.MEMBER

/**
 * Convert the contract [ContractLibrary] (returned by [com.calypsan.listenup.api.LibraryAdminService])
 * to the [Library] domain model.
 *
 * The contract DTO carries no `revision` (that lives on the member-synced
 * projection, not the admin aggregate), so it defaults to 0L.
 */
private fun ContractLibrary.toDomain(): Library =
    Library(
        id = id.value,
        name = name,
        folders = folders.map { LibraryFolderRef(id = it.id.value, rootPath = it.rootPath) },
        metadataPrecedence = metadataPrecedence,
        accessMode =
            when (accessMode) {
                ContractAccessMode.SHARED -> AccessMode.OPEN
                ContractAccessMode.RESTRICTED, ContractAccessMode.PRIVATE -> AccessMode.RESTRICTED
            },
        createdByUserId = createdByUserId?.value,
        createdAt = createdAt,
        revision = 0L,
    )
