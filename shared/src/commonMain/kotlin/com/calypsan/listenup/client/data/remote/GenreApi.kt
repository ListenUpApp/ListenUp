package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.domain.model.Genre
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract interface for genre API operations.
 *
 * Extracted to enable mocking in tests.
 */
interface GenreApiContract {
    /**
     * Get all available genres.
     */
    suspend fun listGenres(): List<Genre>

    /**
     * Set genres for a book (replaces all existing genre associations).
     */
    suspend fun setBookGenres(
        bookId: String,
        genreIds: List<String>,
    )

    /**
     * Get genres for a specific book.
     */
    suspend fun getBookGenres(bookId: String): List<Genre>

    /**
     * Create a new genre.
     */
    suspend fun createGenre(
        name: String,
        parentId: String?,
    ): Genre

    /**
     * Update an existing genre's name.
     */
    suspend fun updateGenre(
        id: String,
        name: String,
    ): Genre

    /**
     * Delete a genre.
     */
    suspend fun deleteGenre(id: String)

    /**
     * Move a genre to a new parent.
     */
    suspend fun moveGenre(
        id: String,
        newParentId: String?,
    )
}

/**
 * API client for genre operations.
 *
 * Genres are system-controlled categories. Users can select from
 * existing genres but cannot create new ones from the client.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class GenreApi(
    private val clientFactory: ApiClientFactory,
) : GenreApiContract {
    /**
     * Get all available genres.
     *
     * Endpoint: GET /api/v1/genres
     */
    override suspend fun listGenres(): List<Genre> {
        val client = clientFactory.getClient()
        val response: ApiResponse<GenreListResponse> = client.get("/api/v1/genres").body()
        return response.dataOrThrow { GenreApiException(it) }.genres.map { it.toDomain() }
    }

    /**
     * Set genres for a book.
     *
     * Endpoint: POST /api/v1/books/{bookId}/genres
     *
     * @param bookId The book to update
     * @param genreIds List of genre IDs to associate
     */
    override suspend fun setBookGenres(
        bookId: String,
        genreIds: List<String>,
    ) {
        val client = clientFactory.getClient()
        client.post("/api/v1/books/$bookId/genres") {
            contentType(ContentType.Application.Json)
            setBody(SetBookGenresRequest(genreIds = genreIds))
        }
    }

    /**
     * Get genres for a specific book.
     *
     * Endpoint: GET /api/v1/books/{bookId}/genres
     *
     * @param bookId The book ID to get genres for
     */
    override suspend fun getBookGenres(bookId: String): List<Genre> {
        val client = clientFactory.getClient()
        val response: ApiResponse<GenreListResponse> = client.get("/api/v1/books/$bookId/genres").body()
        return response.dataOrThrow { GenreApiException(it) }.genres.map { it.toDomain() }
    }

    override suspend fun createGenre(
        name: String,
        parentId: String?,
    ): Genre {
        val client = clientFactory.getClient()
        val response: ApiResponse<GenreResponse> =
            client
                .post("/api/v1/genres") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateGenreRequest(name = name, parentId = parentId))
                }.body()
        return response.dataOrThrow { GenreApiException(it) }.toDomain()
    }

    override suspend fun updateGenre(
        id: String,
        name: String,
    ): Genre {
        val client = clientFactory.getClient()
        val response: ApiResponse<GenreResponse> =
            client
                .put("/api/v1/genres/$id") {
                    contentType(ContentType.Application.Json)
                    setBody(UpdateGenreRequest(name = name))
                }.body()
        return response.dataOrThrow { GenreApiException(it) }.toDomain()
    }

    override suspend fun deleteGenre(id: String) {
        val client = clientFactory.getClient()
        val response: ApiResponse<Unit> = client.delete("/api/v1/genres/$id").body()
        response.dataOrThrow { GenreApiException(it) }
    }

    override suspend fun moveGenre(
        id: String,
        newParentId: String?,
    ) {
        val client = clientFactory.getClient()
        val response: ApiResponse<GenreResponse> =
            client
                .post("/api/v1/genres/$id/move") {
                    contentType(ContentType.Application.Json)
                    setBody(MoveGenreRequest(newParentId = newParentId))
                }.body()
        response.dataOrThrow { GenreApiException(it) }
    }
}

/**
 * Wrapper for genre list response.
 *
 * Server returns: {"genres": [...]}
 * After envelope wrapping: {"success": true, "data": {"genres": [...]}}
 */
@Serializable
internal data class GenreListResponse(
    @SerialName("genres")
    val genres: List<GenreResponse>,
)

/**
 * Genre API response DTO.
 */
@Serializable
internal data class GenreResponse(
    val id: String,
    val name: String,
    val slug: String,
    val path: String,
    @SerialName("book_count")
    val bookCount: Int = 0,
    @SerialName("parent_id")
    val parentId: String? = null,
    val depth: Int = 0,
    @SerialName("sort_order")
    val sortOrder: Int = 0,
    val color: String? = null,
    val icon: String? = null,
    @SerialName("is_system")
    val isSystem: Boolean = false,
) {
    fun toDomain() =
        Genre(
            id = id,
            name = name,
            slug = slug,
            path = path,
            bookCount = bookCount,
        )
}

@Serializable
internal data class SetBookGenresRequest(
    @SerialName("genre_ids")
    val genreIds: List<String>,
)

@Serializable
internal data class CreateGenreRequest(
    val name: String,
    @SerialName("parent_id")
    val parentId: String? = null,
)

@Serializable
internal data class UpdateGenreRequest(
    @SerialName("name")
    val name: String,
)

@Serializable
internal data class MoveGenreRequest(
    @SerialName("new_parent_id")
    val newParentId: String? = null,
)

/**
 * Exception thrown when a genre API call fails.
 */
class GenreApiException(
    message: String,
) : Exception(message)
