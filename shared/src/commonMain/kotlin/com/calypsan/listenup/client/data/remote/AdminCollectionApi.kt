@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.map
import com.calypsan.listenup.client.data.remote.model.AddBooksToCollectionRequest
import com.calypsan.listenup.client.data.remote.model.AdminCollectionResponse
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.data.remote.model.CreateCollectionRequest
import com.calypsan.listenup.client.data.remote.model.UpdateCollectionRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract for admin collection API operations.
 * All methods require authentication as an admin user.
 */
interface AdminCollectionApiContract {
    /**
     * Get all collections.
     *
     * @return [AppResult.Success] containing the list of collections, [AppResult.Failure] on API error
     */
    suspend fun getCollections(): AppResult<List<AdminCollectionResponse>>

    /**
     * Create a new collection.
     *
     * @return [AppResult.Success] containing the created collection, [AppResult.Failure] on API error
     */
    suspend fun createCollection(name: String): AppResult<AdminCollectionResponse>

    /**
     * Get a single collection by ID.
     *
     * @return [AppResult.Success] containing the collection, [AppResult.Failure] on API error
     */
    suspend fun getCollection(collectionId: String): AppResult<AdminCollectionResponse>

    /**
     * Get books in a collection.
     *
     * @return [AppResult.Success] containing the list of books, [AppResult.Failure] on API error
     */
    suspend fun getCollectionBooks(collectionId: String): AppResult<List<CollectionBookResponse>>

    /**
     * Update a collection's name.
     *
     * @return [AppResult.Success] containing the updated collection, [AppResult.Failure] on API error
     */
    suspend fun updateCollection(
        collectionId: String,
        name: String,
    ): AppResult<AdminCollectionResponse>

    /**
     * Delete a collection.
     *
     * @return [AppResult.Success] on deletion, [AppResult.Failure] on API error
     */
    suspend fun deleteCollection(collectionId: String): AppResult<Unit>

    /**
     * Add books to a collection.
     *
     * @return [AppResult.Success] on success, [AppResult.Failure] on API error
     */
    suspend fun addBooks(
        collectionId: String,
        bookIds: List<String>,
    ): AppResult<Unit>

    /**
     * Remove a book from a collection.
     *
     * @return [AppResult.Success] on removal, [AppResult.Failure] on API error
     */
    suspend fun removeBook(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit>

    /**
     * Get shares for a collection.
     *
     * @return [AppResult.Success] containing the list of shares, [AppResult.Failure] on API error
     */
    suspend fun getCollectionShares(collectionId: String): AppResult<List<ShareResponse>>

    /**
     * Share a collection with a user.
     *
     * @return [AppResult.Success] containing the created share, [AppResult.Failure] on API error
     */
    suspend fun shareCollection(
        collectionId: String,
        userId: String,
        permission: String = "read",
    ): AppResult<ShareResponse>

    /**
     * Remove a share.
     *
     * @return [AppResult.Success] on removal, [AppResult.Failure] on API error
     */
    suspend fun deleteShare(shareId: String): AppResult<Unit>
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
    override suspend fun getCollections(): AppResult<List<AdminCollectionResponse>> =
        apiCall(errorMessage = "Admin collections response missing data") {
            clientFactory.getClient().get("/api/v1/admin/collections").body<ApiResponse<CollectionsResponse>>()
        }.map { it.collections }

    override suspend fun createCollection(name: String): AppResult<AdminCollectionResponse> =
        apiCall(errorMessage = "Create collection response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/admin/collections") {
                    setBody(CreateCollectionRequest(name))
                }.body<ApiResponse<AdminCollectionResponse>>()
        }

    override suspend fun getCollection(collectionId: String): AppResult<AdminCollectionResponse> =
        apiCall(errorMessage = "Collection detail response missing data") {
            clientFactory.getClient().get("/api/v1/admin/collections/$collectionId").body<ApiResponse<AdminCollectionResponse>>()
        }

    override suspend fun getCollectionBooks(collectionId: String): AppResult<List<CollectionBookResponse>> =
        apiCall(errorMessage = "Collection books response missing data") {
            clientFactory.getClient().get("/api/v1/collections/$collectionId/books").body<ApiResponse<CollectionBooksResponse>>()
        }.map { it.books }

    override suspend fun updateCollection(
        collectionId: String,
        name: String,
    ): AppResult<AdminCollectionResponse> =
        apiCall(errorMessage = "Update collection response missing data") {
            clientFactory
                .getClient()
                .patch("/api/v1/admin/collections/$collectionId") {
                    setBody(UpdateCollectionRequest(name))
                }.body<ApiResponse<AdminCollectionResponse>>()
        }

    override suspend fun deleteCollection(collectionId: String): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().delete("/api/v1/admin/collections/$collectionId").body<ApiResponse<Unit>>()
        }

    override suspend fun addBooks(
        collectionId: String,
        bookIds: List<String>,
    ): AppResult<Unit> =
        apiCallUnit {
            clientFactory
                .getClient()
                .post("/api/v1/admin/collections/$collectionId/books") {
                    setBody(AddBooksToCollectionRequest(bookIds))
                }.body<ApiResponse<Unit>>()
        }

    override suspend fun removeBook(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().delete("/api/v1/admin/collections/$collectionId/books/$bookId").body<ApiResponse<Unit>>()
        }

    override suspend fun getCollectionShares(collectionId: String): AppResult<List<ShareResponse>> =
        apiCall(errorMessage = "Collection shares response missing data") {
            clientFactory.getClient().get("/api/v1/collections/$collectionId/shares").body<ApiResponse<SharesListResponse>>()
        }.map { it.shares }

    override suspend fun shareCollection(
        collectionId: String,
        userId: String,
        permission: String,
    ): AppResult<ShareResponse> =
        apiCall(errorMessage = "Share collection response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/collections/$collectionId/shares") {
                    setBody(ShareCollectionRequest(userId, permission))
                }.body<ApiResponse<ShareResponse>>()
        }

    override suspend fun deleteShare(shareId: String): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().delete("/api/v1/shares/$shareId").body<ApiResponse<Unit>>()
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
