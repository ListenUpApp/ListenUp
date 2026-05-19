package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.map
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.domain.model.Tag
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.calypsan.listenup.core.Timestamp
import kotlin.time.Instant

/**
 * API client for global tag operations.
 *
 * Tags are community-wide content descriptors that any user can apply
 * to books they have access to.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class TagApi(
    private val clientFactory: ApiClientFactory,
) : TagApiContract {
    /**
     * Get all global tags ordered by popularity.
     *
     * Endpoint: GET /api/v1/tags
     */
    override suspend fun listTags(): AppResult<List<Tag>> =
        apiCall(errorMessage = "Tag list response missing data") {
            clientFactory.getClient().get("/api/v1/tags").body<ApiResponse<ListTagsResponse>>()
        }.map { it.tags.map(TagResponse::toDomain) }

    /**
     * Get a tag by its slug.
     *
     * Endpoint: GET /api/v1/tags/{slug}
     *
     * @param slug The tag slug
     * @return [AppResult.Success] containing the tag, or [AppResult.Failure] on error
     *   (including 404 Not Found, which maps to [com.calypsan.listenup.api.error.TransportError.Server4xx])
     */
    override suspend fun getTagBySlug(slug: String): AppResult<Tag> =
        apiCall(errorMessage = "Tag response missing data") {
            clientFactory.getClient().get("/api/v1/tags/$slug").body<ApiResponse<TagResponse>>()
        }.map { it.toDomain() }

    /**
     * Get tags for a specific book.
     *
     * Endpoint: GET /api/v1/books/{bookId}/tags
     *
     * @param bookId The book ID to get tags for
     */
    override suspend fun getBookTags(bookId: String): AppResult<List<Tag>> =
        apiCall(errorMessage = "Book tags response missing data") {
            clientFactory.getClient().get("/api/v1/books/$bookId/tags").body<ApiResponse<GetBookTagsResponse>>()
        }.map { it.tags.map(TagResponse::toDomain) }

    /**
     * Add a tag to a book. Creates the tag if it doesn't exist.
     *
     * Endpoint: POST /api/v1/books/{bookId}/tags
     *
     * @param bookId The book to tag
     * @param rawInput The tag text (will be normalized to slug by server)
     * @return [AppResult.Success] containing the tag that was added or created
     */
    override suspend fun addTagToBook(
        bookId: String,
        rawInput: String,
    ): AppResult<Tag> =
        apiCall(errorMessage = "Add-tag response missing data") {
            clientFactory
                .getClient()
                .post("/api/v1/books/$bookId/tags") {
                    contentType(ContentType.Application.Json)
                    setBody(AddTagRequest(tag = rawInput))
                }.body<ApiResponse<TagResponse>>()
        }.map { it.toDomain() }

    /**
     * Remove a tag from a book.
     *
     * Endpoint: DELETE /api/v1/books/{bookId}/tags/{slug}
     *
     * @param bookId The book to untag
     * @param slug The tag slug to remove
     */
    override suspend fun removeTagFromBook(
        bookId: String,
        slug: String,
    ): AppResult<Unit> =
        apiCallUnit {
            clientFactory.getClient().delete("/api/v1/books/$bookId/tags/$slug").body<ApiResponse<Unit>>()
        }
}

// === Response DTOs ===

/**
 * Response wrapper for listing tags.
 */
@Serializable
internal data class ListTagsResponse(
    @SerialName("tags")
    val tags: List<TagResponse>,
)

/**
 * Response wrapper for getting book tags.
 */
@Serializable
internal data class GetBookTagsResponse(
    @SerialName("tags")
    val tags: List<TagResponse>,
)

/**
 * Tag API response DTO.
 */
@Serializable
internal data class TagResponse(
    val id: String,
    val slug: String,
    @SerialName("book_count")
    val bookCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null,
) {
    fun toDomain() =
        Tag(
            id = id,
            slug = slug,
            bookCount = bookCount,
            createdAt = createdAt?.let { Timestamp(Instant.parse(it).toEpochMilliseconds()) },
        )
}

// === Request DTOs ===

@Serializable
internal data class AddTagRequest(
    @SerialName("tag")
    val tag: String,
)
