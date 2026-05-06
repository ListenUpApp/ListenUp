@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.core.error.AppException
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
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
    suspend fun getMyShelves(): List<ShelfResponse>

    /**
     * Discover shelves from other users containing accessible books.
     * Returns shelves grouped by owner.
     */
    suspend fun discoverShelves(): List<UserShelvesResponse>

    /**
     * Create a new shelf.
     */
    suspend fun createShelf(
        name: String,
        description: String?,
    ): ShelfResponse

    /**
     * Get a shelf by ID with its books.
     */
    suspend fun getShelf(shelfId: String): ShelfDetailResponse

    /**
     * Update a shelf (owner only).
     */
    suspend fun updateShelf(
        shelfId: String,
        name: String,
        description: String?,
    ): ShelfResponse

    /**
     * Delete a shelf (owner only).
     */
    suspend fun deleteShelf(shelfId: String)

    /**
     * Add books to a shelf (owner only).
     */
    suspend fun addBooks(
        shelfId: String,
        bookIds: List<String>,
    )

    /**
     * Remove a book from a shelf (owner only).
     */
    suspend fun removeBook(
        shelfId: String,
        bookId: String,
    )
}

/**
 * API client for shelf operations.
 *
 * Requires authentication via ApiClientFactory.
 */
class ShelfApi(
    private val clientFactory: ApiClientFactory,
) : ShelfApiContract {
    override suspend fun getMyShelves(): List<ShelfResponse> {
        val client = clientFactory.getClient()
        val response: ApiResponse<ListShelvesResponse> =
            client.get("/api/v1/shelves").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.shelves
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun discoverShelves(): List<UserShelvesResponse> {
        val client = clientFactory.getClient()
        val response: ApiResponse<DiscoverShelvesResponse> =
            client.get("/api/v1/shelves/discover").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.users
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun createShelf(
        name: String,
        description: String?,
    ): ShelfResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ShelfResponse> =
            client
                .post("/api/v1/shelves") {
                    setBody(CreateShelfRequest(name, description ?: ""))
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun getShelf(shelfId: String): ShelfDetailResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ShelfDetailResponse> =
            client.get("/api/v1/shelves/$shelfId").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun updateShelf(
        shelfId: String,
        name: String,
        description: String?,
    ): ShelfResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ShelfResponse> =
            client
                .patch("/api/v1/shelves/$shelfId") {
                    setBody(UpdateShelfRequest(name, description ?: ""))
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun deleteShelf(shelfId: String) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> = client.delete("/api/v1/shelves/$shelfId").body()

        when (val result = response.toResult()) {
            is Success -> { /* Shelf deleted successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
    }

    override suspend fun addBooks(
        shelfId: String,
        bookIds: List<String>,
    ) {
        val client = clientFactory.getClient()
        val response =
            client.post("/api/v1/shelves/$shelfId/books") {
                setBody(AddBooksToShelfRequest(bookIds))
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

    override suspend fun removeBook(
        shelfId: String,
        bookId: String,
    ) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> = client.delete("/api/v1/shelves/$shelfId/books/$bookId").body()

        when (val result = response.toResult()) {
            is Success -> { /* Book removed from shelf successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
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
