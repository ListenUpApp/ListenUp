package com.calypsan.listenup.client.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response model for admin collection endpoints.
 *
 * Represents a collection as returned by the server's admin API.
 * Collections are admin-only features that group books for organizational purposes.
 */
@Serializable
data class AdminCollectionResponse(
    val id: String,
    val name: String,
    @SerialName("book_count") val bookCount: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * Request model for creating a new collection.
 */
@Serializable
data class CreateCollectionRequest(
    @SerialName("name")
    val name: String,
)

/**
 * Request model for updating a collection.
 */
@Serializable
data class UpdateCollectionRequest(
    @SerialName("name")
    val name: String,
)

/**
 * Request model for adding books to a collection.
 */
@Serializable
data class AddBooksToCollectionRequest(
    @SerialName("book_ids") val bookIds: List<String>,
)
