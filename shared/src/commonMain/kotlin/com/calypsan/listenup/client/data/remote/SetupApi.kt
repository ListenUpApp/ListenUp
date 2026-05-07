package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.AppResult
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract for setup API operations.
 * Used during initial server setup to configure the library.
 */
interface SetupApiContract {
    /**
     * Get the current library status.
     * Returns [AppResult.Success] with library status, or [AppResult.Failure] on network/server error.
     */
    suspend fun getLibraryStatus(): AppResult<LibraryStatusResponse>

    /**
     * Browse the server's filesystem to select scan paths.
     * Returns [AppResult.Success] with directory listing, or [AppResult.Failure] on network/server error.
     * @param path The directory path to browse (use "/" for root)
     */
    suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse>

    /**
     * Set up the library with the provided configuration.
     * Returns [AppResult.Success] with created library details, or [AppResult.Failure] on network/server error.
     * Creates the library and initiates the first scan.
     */
    suspend fun setupLibrary(request: SetupLibraryRequest): AppResult<LibrarySetupResponse>
}

/**
 * API client for library setup operations.
 *
 * Requires authentication via ApiClientFactory.
 * These endpoints are typically called during initial server configuration.
 */
class SetupApi(
    private val clientFactory: ApiClientFactory,
) : SetupApiContract {
    override suspend fun getLibraryStatus(): AppResult<LibraryStatusResponse> =
        apiCall(errorMessage = "library status response missing data") {
            clientFactory.getClient().get("/api/v1/library/status").body()
        }

    override suspend fun browseFilesystem(path: String): AppResult<BrowseFilesystemResponse> =
        apiCall(errorMessage = "filesystem browse response missing data") {
            clientFactory.getClient()
                .get("/api/v1/filesystem") {
                    url {
                        parameters.append("path", path)
                    }
                }.body()
        }

    override suspend fun setupLibrary(request: SetupLibraryRequest): AppResult<LibrarySetupResponse> =
        apiCall(errorMessage = "library setup response missing data") {
            clientFactory.getClient()
                .post("/api/v1/library/setup") {
                    setBody(request)
                }.body()
        }
}

// =============================================================================
// Setup API Models
// =============================================================================

/**
 * Response from GET /api/v1/library/status endpoint.
 * Indicates whether the server has a library configured.
 */
@Serializable
data class LibraryStatusResponse(
    @SerialName("exists") val exists: Boolean,
    @SerialName("library") val library: LibrarySetupResponse? = null,
    @SerialName("needs_setup") val needsSetup: Boolean,
    @SerialName("book_count") val bookCount: Int = 0,
    @SerialName("is_scanning") val isScanning: Boolean = false,
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

/**
 * Request to POST /api/v1/library/setup endpoint.
 * Creates a new library with the specified configuration.
 */
@Serializable
data class SetupLibraryRequest(
    @SerialName("name") val name: String,
    @SerialName("scan_paths") val scanPaths: List<String>,
    @SerialName("skip_inbox") val skipInbox: Boolean = false,
)

/**
 * Response from POST /api/v1/library/setup endpoint.
 * Contains the created library details.
 */
@Serializable
data class LibrarySetupResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("scan_paths") val scanPaths: List<String> = emptyList(),
    @SerialName("skip_inbox") val skipInbox: Boolean = false,
    @SerialName("access_mode") val accessMode: String = "open",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)
