
package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.http.encodeURLPath
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract for admin API operations.
 * All methods require authentication as an admin user.
 */
@Suppress("TooManyFunctions")
interface AdminApiContract {
    // User management — bare contract types matching the Kotlin server's REST responses
    suspend fun getUsers(): AppResult<List<User>>

    suspend fun getUser(userId: String): AppResult<User>

    suspend fun updateUser(
        userId: String,
        patch: AdminUserPatch,
    ): AppResult<User>

    suspend fun deleteUser(userId: String): AppResult<Unit>

    // Pending user management
    suspend fun getPendingUsers(): AppResult<List<User>>

    suspend fun decidePendingRegistration(decision: PendingRegistrationDecision): AppResult<PendingRegistrationOutcome>

    // Invite management
    suspend fun getInvites(): AppResult<List<AdminInvite>>

    suspend fun createInvite(request: CreateInviteRequest): AppResult<AdminInvite>

    suspend fun deleteInvite(inviteId: String): AppResult<Unit>

    // Server settings (inbox workflow)
    suspend fun getServerSettings(): AppResult<ServerSettingsResponse>

    suspend fun updateServerSettings(request: ServerSettingsRequest): AppResult<ServerSettingsResponse>

    // Instance settings
    suspend fun updateInstance(request: UpdateInstanceRequest): AppResult<InstanceSettingsResponse>

    // Inbox management
    suspend fun listInboxBooks(): AppResult<InboxBooksResponse>

    suspend fun releaseBooks(bookIds: List<String>): AppResult<ReleaseInboxBooksResponse>

    suspend fun stageCollection(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit>

    suspend fun unstageCollection(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit>

    // Library management
    suspend fun getLibraries(): AppResult<List<LibraryResponse>>

    suspend fun getLibrary(libraryId: String): AppResult<LibraryResponse>

    suspend fun updateLibrary(
        libraryId: String,
        request: UpdateLibraryRequest,
    ): AppResult<LibraryResponse>

    // Scan path management
    suspend fun addScanPath(
        libraryId: String,
        path: String,
    ): AppResult<LibraryResponse>

    suspend fun removeScanPath(
        libraryId: String,
        path: String,
    ): AppResult<LibraryResponse>

    // Manual scan trigger
    suspend fun triggerScan(libraryId: String): AppResult<Unit>

    // Filesystem browsing (reused from setup)
    suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse>
}

private const val ADMIN_USERS_PATH = "/api/v1/admin/users"

private fun userPath(userId: String) = "$ADMIN_USERS_PATH/$userId"

/**
 * API client for admin operations.
 *
 * Requires authentication via [ApiClientFactory].
 * All endpoints require the user to be an admin (IsRoot or Role=admin).
 *
 * User-management methods use [suspendRunCatching] directly because the Kotlin server
 * returns **bare** contract types — not the `ApiResponse` envelope that the Go server
 * used. The [apiCall]/[apiCallUnit] helpers are envelope-shaped and cannot be used here;
 * this is the same pattern as [CollectionInboxApi].
 */
class AdminApi(
    private val clientFactory: ApiClientFactory,
) : AdminApiContract {
    // User Management — bare contract types (Kotlin server returns no ApiResponse envelope)

    override suspend fun getUsers(): AppResult<List<User>> =
        suspendRunCatching {
            clientFactory.getClient().get(ADMIN_USERS_PATH).body<List<User>>()
        }

    override suspend fun getUser(userId: String): AppResult<User> =
        suspendRunCatching {
            clientFactory.getClient().get(userPath(userId)).body<User>()
        }

    override suspend fun updateUser(
        userId: String,
        patch: AdminUserPatch,
    ): AppResult<User> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .patch(userPath(userId)) {
                    setBody(patch)
                }.body<User>()
        }

    override suspend fun deleteUser(userId: String): AppResult<Unit> =
        suspendRunCatching {
            // 204 No Content — read the status to consume the response; no body to decode.
            clientFactory.getClient().delete(userPath(userId)).status
        }.map { }

    // Pending User Management

    override suspend fun getPendingUsers(): AppResult<List<User>> =
        suspendRunCatching {
            clientFactory.getClient().get("$ADMIN_USERS_PATH/pending").body<List<User>>()
        }

    override suspend fun decidePendingRegistration(
        decision: PendingRegistrationDecision,
    ): AppResult<PendingRegistrationOutcome> =
        suspendRunCatching {
            clientFactory
                .getClient()
                .post("$ADMIN_USERS_PATH/pending-decision") {
                    setBody(decision)
                }.body<PendingRegistrationOutcome>()
        }

    // Invite Management

    override suspend fun getInvites(): AppResult<List<AdminInvite>> =
        apiCall(errorMessage = "Admin invites response missing data") {
            clientFactory.getClient().get("/api/v1/admin/invites").body<ApiResponse<InvitesResponse>>()
        }.map { it.invites }

    override suspend fun createInvite(request: CreateInviteRequest): AppResult<AdminInvite> =
        apiCall(errorMessage = "Create invite response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/invites") {
                    setBody(request)
                }.body<ApiResponse<AdminInvite>>()
        }

    override suspend fun deleteInvite(inviteId: String): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().delete("/api/v1/admin/invites/$inviteId").body<ApiResponse<Unit>>()
        }

    // Server Settings (Inbox Workflow)

    override suspend fun getServerSettings(): AppResult<ServerSettingsResponse> =
        apiCall(errorMessage = "Server settings response missing data") {
            clientFactory.getClient().get("/api/v1/admin/settings").body<ApiResponse<ServerSettingsApiResponse>>()
        }.map { it.toDomain() }

    override suspend fun updateServerSettings(request: ServerSettingsRequest): AppResult<ServerSettingsResponse> =
        apiCall(errorMessage = "Update server settings response missing data") {
            clientFactory
                .getClient()
                .patch("/api/v1/admin/settings") {
                    setBody(request.toApiRequest())
                }.body<ApiResponse<ServerSettingsApiResponse>>()
        }.map { it.toDomain() }

    // Instance Management

    override suspend fun updateInstance(request: UpdateInstanceRequest): AppResult<InstanceSettingsResponse> =
        apiCall(errorMessage = "Update instance response missing data") {
            clientFactory
                .getClient()
                .patch("/api/v1/admin/instance") {
                    setBody(request)
                }.body<ApiResponse<InstanceSettingsResponse>>()
        }

    // Inbox Management

    override suspend fun listInboxBooks(): AppResult<InboxBooksResponse> =
        apiCall(errorMessage = "Inbox books response missing data") {
            clientFactory.getClient().get("/api/v1/admin/inbox").body<ApiResponse<InboxBooksApiResponse>>()
        }.map { it.toDomain() }

    override suspend fun releaseBooks(bookIds: List<String>): AppResult<ReleaseInboxBooksResponse> =
        apiCall(errorMessage = "Release inbox books response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/inbox/release") {
                    setBody(ReleaseInboxBooksApiRequest(bookIds))
                }.body<ApiResponse<ReleaseInboxBooksApiResponse>>()
        }.map { it.toDomain() }

    override suspend fun stageCollection(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit> =
        apiCallUnit {
            clientFactory
                .getClient()
                .post("/api/v1/admin/inbox/$bookId/stage") {
                    setBody(StageCollectionApiRequest(collectionId))
                }.body<ApiResponse<Unit>>()
        }

    override suspend fun unstageCollection(
        bookId: String,
        collectionId: String,
    ): AppResult<Unit> =
        apiCallUnit {
            clientFactory
                .getClient()
                .delete(
                    "/api/v1/admin/inbox/$bookId/stage/$collectionId",
                ).body<ApiResponse<Unit>>()
        }

    // Library Management

    override suspend fun getLibraries(): AppResult<List<LibraryResponse>> =
        apiCall(errorMessage = "Admin libraries response missing data") {
            clientFactory.getClient().get("/api/v1/libraries").body<ApiResponse<LibrariesResponse>>()
        }.map { it.libraries }

    override suspend fun getLibrary(libraryId: String): AppResult<LibraryResponse> =
        apiCall(errorMessage = "Library detail response missing data") {
            clientFactory.getClient().get("/api/v1/libraries/$libraryId").body<ApiResponse<LibraryResponse>>()
        }

    override suspend fun updateLibrary(
        libraryId: String,
        request: UpdateLibraryRequest,
    ): AppResult<LibraryResponse> =
        apiCall(errorMessage = "Update library response missing data") {
            clientFactory
                .getClient()
                .patch("/api/v1/libraries/$libraryId") {
                    setBody(request)
                }.body<ApiResponse<LibraryResponse>>()
        }

    // Scan Path Management

    override suspend fun addScanPath(
        libraryId: String,
        path: String,
    ): AppResult<LibraryResponse> =
        apiCall(errorMessage = "Add scan path response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/libraries/$libraryId/scan-paths") {
                    setBody(ScanPathRequest(path))
                }.body<ApiResponse<LibraryResponse>>()
        }

    override suspend fun removeScanPath(
        libraryId: String,
        path: String,
    ): AppResult<LibraryResponse> =
        apiCall(errorMessage = "Remove scan path response missing data") {
            val encodedPath = path.encodeURLPath()
            clientFactory
                .getClient()
                .delete("/api/v1/libraries/$libraryId/scan-paths/$encodedPath")
                .body<ApiResponse<LibraryResponse>>()
        }

    override suspend fun triggerScan(libraryId: String): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().post("/api/v1/libraries/$libraryId/scan").body<ApiResponse<Unit>>()
        }

    override suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse> =
        apiCall(errorMessage = "Browse filesystem response missing data") {
            clientFactory
                .getClient()
                .get("/api/v1/filesystem") {
                    url {
                        parameters.append("path", path)
                    }
                }.body<ApiResponse<BrowseFilesystemResponse>>()
        }
}

// Response wrappers

@Serializable
private data class InvitesResponse(
    @SerialName("invites") val invites: List<AdminInvite>,
)

// Models

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

/**
 * Lifecycle state of an [AdminInvite] as surfaced to the admin UI. Currently derived
 * from `claimedAt` only; [EXPIRED] and [REVOKED] are reserved for future server-side
 * status reporting.
 */
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

/**
 * Response from GET /api/v1/filesystem endpoint.
 * Lists directories available for selection as scan paths.
 */
@Serializable
data class BrowseFilesystemResponse(
    @SerialName("path") val path: String,
    @SerialName("parent") val parent: String? = null,
    @SerialName("entries") val entries: List<DirectoryEntryResponse> = emptyList(),
    @SerialName("is_root") val isRoot: Boolean = false,
)

/**
 * A directory entry in the filesystem browser.
 */
@Serializable
data class DirectoryEntryResponse(
    @SerialName("name") val name: String,
    @SerialName("path") val path: String,
)
