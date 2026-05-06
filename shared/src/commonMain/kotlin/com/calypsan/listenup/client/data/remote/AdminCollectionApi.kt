@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.remote.model.AddBooksToCollectionRequest
import com.calypsan.listenup.client.data.remote.model.AdminCollectionResponse
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.data.remote.model.CreateCollectionRequest
import com.calypsan.listenup.client.data.remote.model.UpdateCollectionRequest
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
 * Contract for admin collection API operations.
 * All methods require authentication as an admin user.
 */
interface AdminCollectionApiContract {
    /**
     * Get all collections.
     */
    suspend fun getCollections(): List<AdminCollectionResponse>

    /**
     * Create a new collection.
     */
    suspend fun createCollection(name: String): AdminCollectionResponse

    /**
     * Get a single collection by ID.
     */
    suspend fun getCollection(collectionId: String): AdminCollectionResponse

    /**
     * Get books in a collection.
     */
    suspend fun getCollectionBooks(collectionId: String): List<CollectionBookResponse>

    /**
     * Update a collection's name.
     */
    suspend fun updateCollection(
        collectionId: String,
        name: String,
    ): AdminCollectionResponse

    /**
     * Delete a collection.
     */
    suspend fun deleteCollection(collectionId: String)

    /**
     * Add books to a collection.
     */
    suspend fun addBooks(
        collectionId: String,
        bookIds: List<String>,
    )

    /**
     * Remove a book from a collection.
     */
    suspend fun removeBook(
        collectionId: String,
        bookId: String,
    )

    /**
     * Get shares for a collection.
     */
    suspend fun getCollectionShares(collectionId: String): List<ShareResponse>

    /**
     * Share a collection with a user.
     */
    suspend fun shareCollection(
        collectionId: String,
        userId: String,
        permission: String = "read",
    ): ShareResponse

    /**
     * Remove a share.
     */
    suspend fun deleteShare(shareId: String)
}

/**
 * API client for admin collection operations.
 *
 * Requires authentication via ApiClientFactory.
 * All endpoints require the user to be an admin (IsRoot or Role=admin).
 */
class AdminCollectionApi(
    private val clientFactory: ApiClientFactory,
) : AdminCollectionApiContract {
    override suspend fun getCollections(): List<AdminCollectionResponse> {
        val client = clientFactory.getClient()
        val response: ApiResponse<CollectionsResponse> =
            client.get("/api/v1/admin/collections").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.collections
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun createCollection(name: String): AdminCollectionResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<AdminCollectionResponse> =
            client
                .post("/api/v1/admin/collections") {
                    setBody(CreateCollectionRequest(name))
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun getCollection(collectionId: String): AdminCollectionResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<AdminCollectionResponse> =
            client.get("/api/v1/admin/collections/$collectionId").body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun getCollectionBooks(collectionId: String): List<CollectionBookResponse> {
        val client = clientFactory.getClient()
        val response: ApiResponse<CollectionBooksResponse> =
            client.get("/api/v1/collections/$collectionId/books").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.books
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun updateCollection(
        collectionId: String,
        name: String,
    ): AdminCollectionResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<AdminCollectionResponse> =
            client
                .patch("/api/v1/admin/collections/$collectionId") {
                    setBody(UpdateCollectionRequest(name))
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun deleteCollection(collectionId: String) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> = client.delete("/api/v1/admin/collections/$collectionId").body()

        when (val result = response.toResult()) {
            is Success -> { /* Collection deleted successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
    }

    override suspend fun addBooks(
        collectionId: String,
        bookIds: List<String>,
    ) {
        val client = clientFactory.getClient()
        val response =
            client.post("/api/v1/admin/collections/$collectionId/books") {
                setBody(AddBooksToCollectionRequest(bookIds))
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
        // 200 OK with message - success
    }

    override suspend fun removeBook(
        collectionId: String,
        bookId: String,
    ) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> =
            client.delete("/api/v1/admin/collections/$collectionId/books/$bookId").body()

        when (val result = response.toResult()) {
            is Success -> { /* Book removed from collection successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
    }

    override suspend fun getCollectionShares(collectionId: String): List<ShareResponse> {
        val client = clientFactory.getClient()
        val response: ApiResponse<SharesListResponse> =
            client.get("/api/v1/collections/$collectionId/shares").body()

        return when (val result = response.toResult()) {
            is Success -> result.data.shares
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun shareCollection(
        collectionId: String,
        userId: String,
        permission: String,
    ): ShareResponse {
        val client = clientFactory.getClient()
        val response: ApiResponse<ShareResponse> =
            client
                .post("/api/v1/collections/$collectionId/shares") {
                    setBody(ShareCollectionRequest(userId, permission))
                }.body()

        return when (val result = response.toResult()) {
            is Success -> result.data
            is Failure -> throw AppException(result.error)
        }
    }

    override suspend fun deleteShare(shareId: String) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> = client.delete("/api/v1/shares/$shareId").body()

        when (val result = response.toResult()) {
            is Success -> { /* Share deleted successfully */ }

            is Failure -> {
                throw AppException(result.error)
            }
        }
    }
}

/**
 * Response wrapper for collections list endpoint.
 */
@Serializable
private data class CollectionsResponse(
    @SerialName("collections") val collections: List<AdminCollectionResponse>,
)

/**
 * Response wrapper for collection books endpoint.
 */
@Serializable
private data class CollectionBooksResponse(
    @SerialName("books") val books: List<CollectionBookResponse>,
)

/**
 * A book in a collection.
 */
@Serializable
data class CollectionBookResponse(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("cover_path") val coverPath: String? = null,
)

/**
 * Response wrapper for shares list endpoint.
 */
@Serializable
private data class SharesListResponse(
    @SerialName("shares") val shares: List<ShareResponse>,
)

/**
 * Request body for sharing a collection.
 */
@Serializable
private data class ShareCollectionRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("permission") val permission: String,
)

/**
 * A collection share.
 */
@Serializable
data class ShareResponse(
    @SerialName("id") val id: String,
    @SerialName("collection_id") val collectionId: String,
    @SerialName("shared_with_user_id") val sharedWithUserId: String,
    @SerialName("shared_by_user_id") val sharedByUserId: String,
    @SerialName("permission") val permission: String,
    @SerialName("created_at") val createdAt: kotlin.time.Instant,
)
