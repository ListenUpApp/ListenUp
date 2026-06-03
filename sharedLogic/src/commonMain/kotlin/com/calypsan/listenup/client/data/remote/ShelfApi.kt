
package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract for shelf API operations.
 * All methods require authentication.
 */
interface ShelfApiContract {
    /**
     * Get all shelves owned by the current user.
     */
    suspend fun getMyShelves(): AppResult<List<ShelfResponse>>

    /**
     * Discover shelves from other users containing accessible books.
     * Returns shelves grouped by owner.
     */
    suspend fun discoverShelves(): AppResult<List<UserShelvesResponse>>

    /**
     * Create a new shelf.
     */
    suspend fun createShelf(
        name: String,
        description: String?,
    ): AppResult<ShelfResponse>

    /**
     * Get a shelf by ID with its books.
     */
    suspend fun getShelf(shelfId: String): AppResult<ShelfDetailResponse>

    /**
     * Update a shelf (owner only).
     */
    suspend fun updateShelf(
        shelfId: String,
        name: String,
        description: String?,
    ): AppResult<ShelfResponse>

    /**
     * Delete a shelf (owner only).
     */
    suspend fun deleteShelf(shelfId: String): AppResult<Unit>

    /**
     * Add books to a shelf (owner only).
     */
    suspend fun addBooks(
        shelfId: String,
        bookIds: List<String>,
    ): AppResult<Unit>

    /**
     * Remove a book from a shelf (owner only).
     */
    suspend fun removeBook(
        shelfId: String,
        bookId: String,
    ): AppResult<Unit>
}

private const val SHELVES_PATH = "/api/v1/shelves"

private fun shelfPath(shelfId: String) = "$SHELVES_PATH/$shelfId"

/**
 * API client for shelf operations.
 *
 * Requires authentication via ApiClientFactory.
 */
class ShelfApi(
    private val clientFactory: ApiClientFactory,
) : ShelfApiContract {
    override suspend fun getMyShelves(): AppResult<List<ShelfResponse>> =
        apiCall(errorMessage = "My shelves response missing data") {
            clientFactory.getClient().get(SHELVES_PATH).body<ApiResponse<ListShelvesResponse>>()
        }.map { it.shelves }

    override suspend fun discoverShelves(): AppResult<List<UserShelvesResponse>> =
        apiCall(errorMessage = "Discover shelves response missing data") {
            clientFactory.getClient().get("$SHELVES_PATH/discover").body<ApiResponse<DiscoverShelvesResponse>>()
        }.map { it.users }

    override suspend fun createShelf(
        name: String,
        description: String?,
    ): AppResult<ShelfResponse> =
        apiCall(errorMessage = "Create shelf response missing data") {
            clientFactory
                .getClient()
                .post(SHELVES_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(CreateShelfRequest(name, description ?: ""))
                }.body<ApiResponse<ShelfResponse>>()
        }

    override suspend fun getShelf(shelfId: String): AppResult<ShelfDetailResponse> =
        apiCall(errorMessage = "Shelf detail response missing data") {
            clientFactory.getClient().get(shelfPath(shelfId)).body<ApiResponse<ShelfDetailResponse>>()
        }

    override suspend fun updateShelf(
        shelfId: String,
        name: String,
        description: String?,
    ): AppResult<ShelfResponse> =
        apiCall(errorMessage = "Update shelf response missing data") {
            clientFactory
                .getClient()
                .patch(shelfPath(shelfId)) {
                    contentType(ContentType.Application.Json)
                    setBody(UpdateShelfRequest(name, description ?: ""))
                }.body<ApiResponse<ShelfResponse>>()
        }

    override suspend fun deleteShelf(shelfId: String): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().delete(shelfPath(shelfId)).body<ApiResponse<Unit>>()
        }

    override suspend fun addBooks(
        shelfId: String,
        bookIds: List<String>,
    ): AppResult<Unit> =
        apiCallUnit {
            clientFactory
                .getClient()
                .post("$SHELVES_PATH/$shelfId/books") {
                    contentType(ContentType.Application.Json)
                    setBody(AddBooksToShelfRequest(bookIds))
                }.body<ApiResponse<Unit>>()
        }

    override suspend fun removeBook(
        shelfId: String,
        bookId: String,
    ): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().delete("$SHELVES_PATH/$shelfId/books/$bookId").body<ApiResponse<Unit>>()
        }
}

// ========== Response Models ==========

/**
 * Shelf owner information.
 */
@Serializable
data class ShelfOwnerResponse(
    @SerialName("id") val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_color") val avatarColor: String,
)

/**
 * Shelf summary response.
 */
@Serializable
data class ShelfResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("owner") val owner: ShelfOwnerResponse,
    @SerialName("book_count") val bookCount: Int,
    @SerialName("total_duration") val totalDuration: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * A book within a shelf.
 */
@Serializable
data class ShelfBookResponse(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("author_names") val authorNames: List<String>,
    @SerialName("cover_path") val coverPath: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Long,
)

/**
 * Shelf detail response with books.
 */
@Serializable
data class ShelfDetailResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("owner") val owner: ShelfOwnerResponse,
    @SerialName("book_count") val bookCount: Int,
    @SerialName("total_duration") val totalDuration: Long,
    @SerialName("books") val books: List<ShelfBookResponse>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * A user's shelves in discover response.
 */
@Serializable
data class UserShelvesResponse(
    @SerialName("user") val user: ShelfOwnerResponse,
    @SerialName("shelves") val shelves: List<ShelfResponse>,
)

// ========== Request Models ==========

/**
 * Request to create a shelf.
 */
@Serializable
private data class CreateShelfRequest(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
)

/**
 * Request to update a shelf.
 */
@Serializable
private data class UpdateShelfRequest(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
)

/**
 * Request to add books to a shelf.
 */
@Serializable
private data class AddBooksToShelfRequest(
    @SerialName("book_ids") val bookIds: List<String>,
)

// ========== Internal Response Wrappers ==========

/**
 * Wrapper for list shelves response.
 */
@Serializable
private data class ListShelvesResponse(
    @SerialName("shelves") val shelves: List<ShelfResponse>,
)

/**
 * Wrapper for discover shelves response.
 */
@Serializable
private data class DiscoverShelvesResponse(
    @SerialName("users") val users: List<UserShelvesResponse>,
)
