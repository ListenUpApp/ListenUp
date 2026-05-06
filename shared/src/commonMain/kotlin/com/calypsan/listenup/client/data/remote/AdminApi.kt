@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.core.error.AppException
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.http.encodeURLPath
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract for admin API operations.
 * All methods require authentication as an admin user.
 */
@Suppress("TooManyFunctions")
interface AdminApiContract {
    // User management
    suspend fun getUsers(): List<AdminUser>

    suspend fun getUser(userId: String): AdminUser

    suspend fun updateUser(
        userId: String,
        request: UpdateUserRequest,
    ): AdminUser

    suspend fun deleteUser(userId: String)

    // Pending user management
    suspend fun getPendingUsers(): List<AdminUser>

    suspend fun approveUser(userId: String): AdminUser

    suspend fun denyUser(userId: String)

    // Invite management
    suspend fun getInvites(): List<AdminInvite>

    suspend fun createInvite(request: CreateInviteRequest): AdminInvite

    suspend fun deleteInvite(inviteId: String)

    // Settings
    suspend fun setOpenRegistration(enabled: Boolean)

    // Server settings (inbox workflow)
    suspend fun getServerSettings(): ServerSettingsResponse

    suspend fun updateServerSettings(request: ServerSettingsRequest): ServerSettingsResponse

    // Instance settings
    suspend fun updateInstance(request: UpdateInstanceRequest): InstanceSettingsResponse

    // Inbox management
    suspend fun listInboxBooks(): InboxBooksResponse

    suspend fun releaseBooks(bookIds: List<String>): ReleaseInboxBooksResponse

    suspend fun stageCollection(
        bookId: String,
        collectionId: String,
    )

    suspend fun unstageCollection(
        bookId: String,
        collectionId: String,
    )

    // Library management
    suspend fun getLibraries(): List<LibraryResponse>

    suspend fun getLibrary(libraryId: String): LibraryResponse

    suspend fun updateLibrary(
        libraryId: String,
        request: UpdateLibraryRequest,
    ): LibraryResponse

    // Scan path management
    suspend fun addScanPath(
        libraryId: String,
        path: String,
    ): LibraryResponse

    suspend fun removeScanPath(
        libraryId: String,
        path: String,
    ): LibraryResponse

    // Manual scan trigger
    suspend fun triggerScan(libraryId: String)

    // Filesystem browsing (reused from setup)
    suspend fun browseFilesystem(path: String): BrowseFilesystemResponse
}

/**
 * API client for admin operations.
 *
 * Requires authentication via ApiClientFactory.
 * All endpoints require the user to be an admin (IsRoot or Role=admin).
 */
class AdminApi(
    private val clientFactory: ApiClientFactory,
) : AdminApiContract {
    // User Management

    override suspend fun getUsers(): List<AdminUser> {
        val client = clientFactory.getClient()
        val response: ApiResponse<UsersResponse> =
            client.get("/api/v1/admin/users").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.users
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun getUser(userId: String): AdminUser {
        val client = clientFactory.getClient()
        val response: ApiResponse<AdminUser> =
            client.get("/api/v1/admin/users/$userId").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun updateUser(
        userId: String,
        request: UpdateUserRequest,
    ): AdminUser {
        val client = clientFactory.getClient()
        val response: ApiResponse<AdminUser> =
            client
                .patch("/api/v1/admin/users/$userId") {
                    setBody(request)
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun deleteUser(userId: String) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> = client.delete("/api/v1/admin/users/$userId").body()

        when (val result = response.toResult()) {
            is Success -> { /* User deleted successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
    }

    // Pending User Management

    override suspend fun getPendingUsers(): List<AdminUser> {
        val client = clientFactory.getClient()
        val response: ApiResponse<UsersResponse> =
            client.get("/api/v1/admin/users/pending").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.users
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun approveUser(userId: String): AdminUser {
        val client = clientFactory.getClient()
        val response: ApiResponse<AdminUser> =
            client.post("/api/v1/admin/users/$userId/approve").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun denyUser(userId: String) {
        val client = clientFactory.getClient()
        val response = client.post("/api/v1/admin/users/$userId/deny")

        if (!response.status.isSuccess()) {
            val errorResponse: ApiResponse<Unit> = response.body()
            when (val result = errorResponse.toResult()) {
                is Success -> { /* Shouldn't happen */ }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
        }
    }

    // Invite Management

    override suspend fun getInvites(): List<AdminInvite> {
        val client = clientFactory.getClient()
        val response: ApiResponse<InvitesResponse> =
            client.get("/api/v1/admin/invites").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.invites
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun createInvite(request: CreateInviteRequest): AdminInvite {
        val client = clientFactory.getClient()
        val response: ApiResponse<AdminInvite> =
            client
                .post("/api/v1/admin/invites") {
                    setBody(request)
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun deleteInvite(inviteId: String) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> = client.delete("/api/v1/admin/invites/$inviteId").body()

        when (val result = response.toResult()) {
            is Success -> { /* Invite deleted successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
    }

    // Settings

    override suspend fun setOpenRegistration(enabled: Boolean) {
        val client = clientFactory.getClient()
        val response =
            client.put("/api/v1/admin/settings/open-registration") {
                setBody(SetOpenRegistrationRequest(enabled))
            }

        if (!response.status.isSuccess()) {
            val errorResponse: ApiResponse<Unit> = response.body()
            when (val result = errorResponse.toResult()) {
                is Success -> { /* Shouldn't happen */ }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
        }
    }

    // Server Settings (Inbox Workflow)

    override suspend fun getServerSettings(): ServerSettingsResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ServerSettingsApiResponse> =
            client.get("/api/v1/admin/settings").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.toDomain()
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun updateServerSettings(request: ServerSettingsRequest): ServerSettingsResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ServerSettingsApiResponse> =
            client
                .patch("/api/v1/admin/settings") {
                    setBody(request.toApiRequest())
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data.toDomain()
            is Failure -> throw AppException(result.error)
        }
    }

    // Instance Management

    override suspend fun updateInstance(request: UpdateInstanceRequest): InstanceSettingsResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<InstanceSettingsResponse> =
            client
                .patch("/api/v1/admin/instance") {
                    setBody(request)
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    // Inbox Management

    override suspend fun listInboxBooks(): InboxBooksResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<InboxBooksApiResponse> =
            client.get("/api/v1/admin/inbox").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.toDomain()
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun releaseBooks(bookIds: List<String>): ReleaseInboxBooksResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ReleaseInboxBooksApiResponse> =
            client
                .post("/api/v1/admin/inbox/release") {
                    setBody(ReleaseInboxBooksApiRequest(bookIds))
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data.toDomain()
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun stageCollection(
        bookId: String,
        collectionId: String,
    ) {
        val client = clientFactory.getClient()
        val response =
            client.post("/api/v1/admin/inbox/$bookId/stage") {
                setBody(StageCollectionApiRequest(collectionId))
            }

        if (!response.status.isSuccess()) {
            val errorResponse: ApiResponse<Unit> = response.body()
            when (val result = errorResponse.toResult()) {
                is Success -> { /* Shouldn't happen */ }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
        }
    }

    override suspend fun unstageCollection(
        bookId: String,
        collectionId: String,
    ) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> =
            client.delete("/api/v1/admin/inbox/$bookId/stage/$collectionId").body()

        when (val result = response.toResult()) {
            is Success -> { /* Collection unstaged successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
    }

    // Library Management

    override suspend fun getLibraries(): List<LibraryResponse> {
        val client = clientFactory.getClient()
        val response: ApiResponse<LibrariesResponse> =
            client.get("/api/v1/libraries").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.libraries
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun getLibrary(libraryId: String): LibraryResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<LibraryResponse> =
            client.get("/api/v1/libraries/$libraryId").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun updateLibrary(
        libraryId: String,
        request: UpdateLibraryRequest,
    ): LibraryResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<LibraryResponse> =
            client
                .patch("/api/v1/libraries/$libraryId") {
                    setBody(request)
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    // Scan Path Management

    override suspend fun addScanPath(
        libraryId: String,
        path: String,
    ): LibraryResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<LibraryResponse> =
            client
                .post("/api/v1/libraries/$libraryId/scan-paths") {
                    setBody(ScanPathRequest(path))
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun removeScanPath(
        libraryId: String,
        path: String,
    ): LibraryResponse {
        val client = clientFactory.getClient()
        val encodedPath = path.encodeURLPath()
        val response: ApiResponse<LibraryResponse> =
            client
                .delete("/api/v1/libraries/$libraryId/scan-paths/$encodedPath")
                .body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun triggerScan(libraryId: String) {
        val client = clientFactory.getClient()
        val response = client.post("/api/v1/libraries/$libraryId/scan")

        if (!response.status.isSuccess()) {
            val errorResponse: ApiResponse<Unit> = response.body()
            when (val result = errorResponse.toResult()) {
                is Success -> { /* Shouldn't happen */ }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
        }
    }

    override suspend fun browseFilesystem(path: String): BrowseFilesystemResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<BrowseFilesystemResponse> =
            client
                .get("/api/v1/filesystem") {
                    url {
                        parameters.append("path", path)
                    }
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }
}

// Response wrappers

@Serializable
private data class UsersResponse(
    @SerialName("users") val users: List<AdminUser>,
)

@Serializable
private data class InvitesResponse(
    @SerialName("invites") val invites: List<AdminInvite>,
)

// Models

/**
 * Admin view of a user.
 * Contains more information than the regular user model.
 */
@Serializable
data class AdminUser(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("is_root") val isRoot: Boolean,
    @SerialName("role") val role: String,
    @SerialName("status") val status: String = "active",
    @SerialName("permissions") val permissions: UserPermissionsResponse = UserPermissionsResponse(),
    @SerialName("invited_by") val invitedBy: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
) {
    /**
     * Check if this user can be modified/deleted by the current admin.
     * Root users cannot be modified except by themselves.
     */
    val isProtected: Boolean get() = isRoot

    /**
     * Whether this user is pending admin approval.
     */
    val isPending: Boolean get() = status == "pending"
}

/**
 * User permission flags returned by the server.
 */
@Serializable
data class UserPermissionsResponse(
    @SerialName("can_share") val canShare: Boolean = true,
)

/**
 * Admin view of an invite.
 */
@Serializable
data class AdminInvite(
    @SerialName("id") val id: String,
    @SerialName("code") val code: String,
    @SerialName("name") val name: String,
    @SerialName("email") val email: String,
    @SerialName("role") val role: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("claimed_at") val claimedAt: String? = null,
    @SerialName("claimed_by") val claimedBy: String? = null,
    @SerialName("url") val url: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    /**
     * Human-readable status of the invite.
     */
    val status: InviteStatus
        get() = if (claimedAt != null) InviteStatus.CLAIMED else InviteStatus.PENDING
}

enum class InviteStatus {
    PENDING,
    CLAIMED,
    EXPIRED,
    REVOKED,
}

/**
 * Request to create a new invite.
 */
@Serializable
data class CreateInviteRequest(
    @SerialName("name") val name: String,
    @SerialName("email") val email: String,
    @SerialName("role") val role: String = "member",
    @SerialName("expires_in_days") val expiresInDays: Int = 7,
)

/**
 * Request to update a user.
 */
@Serializable
data class UpdateUserRequest(
    @SerialName("role") val role: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("permissions") val permissions: UpdatePermissionsRequest? = null,
)

/**
 * Request to update user permissions.
 * Only include fields that should be changed.
 */
@Serializable
data class UpdatePermissionsRequest(
    @SerialName("can_share") val canShare: Boolean? = null,
)

/**
 * Request to set open registration setting.
 */
@Serializable
data class SetOpenRegistrationRequest(
    @SerialName("enabled") val enabled: Boolean,
)

// =============================================================================
// Server Settings API Models
// =============================================================================

/**
 * API response for server settings endpoint.
 */
@Serializable
private data class ServerSettingsApiResponse(
    @SerialName("server_name") val serverName: String,
    @SerialName("inbox_enabled") val inboxEnabled: Boolean,
    @SerialName("inbox_count") val inboxCount: Int,
) {
    fun toDomain(): ServerSettingsResponse =
        ServerSettingsResponse(
            serverName = serverName,
            inboxEnabled = inboxEnabled,
            inboxCount = inboxCount,
        )
}

/**
 * API request for updating server settings.
 */
@Serializable
private data class ServerSettingsApiRequest(
    @SerialName("server_name") val serverName: String?,
    @SerialName("inbox_enabled") val inboxEnabled: Boolean?,
)

private fun ServerSettingsRequest.toApiRequest(): ServerSettingsApiRequest =
    ServerSettingsApiRequest(serverName = serverName, inboxEnabled = inboxEnabled)

// =============================================================================
// Instance Settings API Models
// =============================================================================

/**
 * Request to update instance settings (PATCH semantics).
 */
@Serializable
data class UpdateInstanceRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("remote_url") val remoteUrl: String? = null,
)

/**
 * Response from instance settings update.
 */
@Serializable
data class InstanceSettingsResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("remote_url") val remoteUrl: String? = null,
)

// =============================================================================
// Inbox API Models
// =============================================================================

/**
 * API response for listing inbox books.
 */
@Serializable
private data class InboxBooksApiResponse(
    @SerialName("books") val books: List<InboxBookApiResponse>,
    @SerialName("total") val total: Int,
) {
    fun toDomain(): InboxBooksResponse =
        InboxBooksResponse(
            books = books.map { it.toDomain() },
            total = total,
        )
}

/**
 * API response for a single inbox book.
 */
@Serializable
private data class InboxBookApiResponse(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("author") val author: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("duration") val duration: Long,
    @SerialName("staged_collection_ids") val stagedCollectionIds: List<String>,
    @SerialName("staged_collections") val stagedCollections: List<CollectionRefApiResponse>,
    @SerialName("scanned_at") val scannedAt: String,
) {
    fun toDomain(): InboxBookResponse =
        InboxBookResponse(
            id = id,
            title = title,
            author = author,
            coverUrl = coverUrl,
            duration = duration,
            stagedCollectionIds = stagedCollectionIds,
            stagedCollections = stagedCollections.map { it.toDomain() },
            scannedAt = scannedAt,
        )
}

/**
 * API response for collection reference.
 */
@Serializable
private data class CollectionRefApiResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
) {
    fun toDomain(): CollectionRef = CollectionRef(id = id, name = name)
}

/**
 * API request for releasing inbox books.
 */
@Serializable
private data class ReleaseInboxBooksApiRequest(
    @SerialName("book_ids") val bookIds: List<String>,
)

/**
 * API response for releasing inbox books.
 */
@Serializable
private data class ReleaseInboxBooksApiResponse(
    @SerialName("released") val released: Int,
    @SerialName("public") val public: Int,
    @SerialName("to_collections") val toCollections: Int,
) {
    fun toDomain(): ReleaseInboxBooksResponse =
        ReleaseInboxBooksResponse(
            released = released,
            public = public,
            toCollections = toCollections,
        )
}

/**
 * API request for staging a collection.
 */
@Serializable
private data class StageCollectionApiRequest(
    @SerialName("collection_id") val collectionId: String,
)

// =============================================================================
// Library API Models
// =============================================================================

/**
 * Response from GET /api/v1/libraries endpoint.
 */
@Serializable
data class LibrariesResponse(
    @SerialName("libraries") val libraries: List<LibraryResponse>,
)

/**
 * Library information returned by the server.
 */
@Serializable
data class LibraryResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("scan_paths") val scanPaths: List<String> = emptyList(),
    @SerialName("skip_inbox") val skipInbox: Boolean = false,
    @SerialName("access_mode") val accessMode: String = "open",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

/**
 * Request to add or remove a scan path.
 */
@Serializable
data class ScanPathRequest(
    @SerialName("path") val path: String,
)

/**
 * Request to update library settings.
 */
@Serializable
data class UpdateLibraryRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("skip_inbox") val skipInbox: Boolean? = null,
    @SerialName("access_mode") val accessMode: String? = null,
)
