package com.calypsan.listenup.server.hardcover

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Who a token belongs to on Hardcover. */
data class HardcoverMe(
    val id: Long,
    val username: String,
)

/** Outcome of [HardcoverGraphQlClient.me]. */
sealed interface MeResult {
    /** The token's owner. */
    data class Found(
        val me: HardcoverMe,
    ) : MeResult

    /** Hardcover rejected the token. */
    data object Unauthorized : MeResult

    /** Transient failure. */
    data class Unavailable(
        val detail: String,
    ) : MeResult
}

/** Outcome of an exact edition lookup ([HardcoverGraphQlClient.editionRatingByAsin] and its ISBN twin). */
sealed interface HardcoverRatingResult {
    /** Hardcover's readers rated the book: [average] out of 5 across [count] ratings. */
    data class Found(
        val average: Double,
        val count: Int,
        val title: String,
        val author: String?,
    ) : HardcoverRatingResult

    /** No such edition, or nobody has rated its book. */
    data object NotFound : HardcoverRatingResult

    /** Hardcover rejected the token. */
    data object Unauthorized : HardcoverRatingResult

    /** Transient failure. */
    data class Unavailable(
        val detail: String,
    ) : HardcoverRatingResult
}

/** One book Hardcover knows by a title; [average] is null while [count] is 0. */
data class HardcoverBookCandidate(
    val title: String,
    val author: String?,
    val average: Double?,
    val count: Int,
)

/** Outcome of [HardcoverGraphQlClient.booksByTitle]. */
sealed interface HardcoverCandidatesResult {
    /** Books titled exactly as asked, most-rated first; possibly empty. */
    data class Found(
        val candidates: List<HardcoverBookCandidate>,
    ) : HardcoverCandidatesResult

    /** Hardcover rejected the token. */
    data object Unauthorized : HardcoverCandidatesResult

    /** Transient failure. */
    data class Unavailable(
        val detail: String,
    ) : HardcoverCandidatesResult
}

/**
 * Hardcover's GraphQL API: `me` and the rating lookups. Every failure is a typed result — a
 * transport error never escapes, and [CancellationException] is rethrown.
 */
class HardcoverGraphQlClient(
    private val http: HttpClient,
    private val apiBaseUrl: String = HARDCOVER_API_BASE_URL,
) {
    /** The account [accessToken] belongs to. Hardcover answers `me` as a list; the first entry is the owner. */
    suspend fun me(accessToken: String): MeResult =
        execute(
            accessToken,
            ME_QUERY,
            JsonObject(emptyMap()),
            "me",
            unauthorized = MeResult.Unauthorized,
            unavailable = { MeResult.Unavailable(it) },
        ) { body ->
            hardcoverJson
                .decodeFromString<MeResponse>(body)
                .data
                ?.me
                ?.firstOrNull()
                ?.let { MeResult.Found(HardcoverMe(it.id, it.username)) }
                ?: MeResult.Unavailable("me: empty response")
        }

    /** How Hardcover's readers rate the book behind the edition with Audible [asin]. */
    suspend fun editionRatingByAsin(
        accessToken: String,
        asin: String,
    ): HardcoverRatingResult = editionRating(accessToken, EDITION_BY_ASIN_QUERY, "asin", asin)

    /** How Hardcover's readers rate the book behind the edition with [isbn] (ISBN-13). */
    suspend fun editionRatingByIsbn(
        accessToken: String,
        isbn: String,
    ): HardcoverRatingResult = editionRating(accessToken, EDITION_BY_ISBN_QUERY, "isbn", isbn)

    /** Up to five books titled exactly [title], most-rated first, for the caller to match by author. */
    suspend fun booksByTitle(
        accessToken: String,
        title: String,
    ): HardcoverCandidatesResult =
        execute(
            accessToken,
            BOOKS_BY_TITLE_QUERY,
            buildJsonObject { put("title", title) },
            "booksByTitle",
            unauthorized = HardcoverCandidatesResult.Unauthorized,
            unavailable = { HardcoverCandidatesResult.Unavailable(it) },
        ) { body ->
            val books =
                hardcoverJson
                    .decodeFromString<BooksResponse>(body)
                    .data
                    ?.books
                    .orEmpty()
            HardcoverCandidatesResult.Found(books.map { it.toCandidate() })
        }

    private suspend fun editionRating(
        accessToken: String,
        query: String,
        variableName: String,
        value: String,
    ): HardcoverRatingResult =
        execute(
            accessToken,
            query,
            buildJsonObject { put(variableName, value) },
            "edition rating",
            unauthorized = HardcoverRatingResult.Unauthorized,
            unavailable = { HardcoverRatingResult.Unavailable(it) },
        ) { body ->
            val book =
                hardcoverJson
                    .decodeFromString<EditionsResponse>(body)
                    .data
                    ?.editions
                    ?.firstOrNull()
                    ?.book
            val candidate = book?.toCandidate()
            val average = candidate?.average
            if (candidate == null || average == null || candidate.count <= 0) {
                HardcoverRatingResult.NotFound
            } else {
                HardcoverRatingResult.Found(average, candidate.count, candidate.title, candidate.author)
            }
        }

    private suspend fun <T> execute(
        accessToken: String,
        query: String,
        variables: JsonObject,
        label: String,
        unauthorized: T,
        unavailable: (String) -> T,
        onSuccess: (String) -> T,
    ): T =
        try {
            val response =
                http.post("$apiBaseUrl/v1/graphql") {
                    bearerAuth(accessToken)
                    header(HttpHeaders.UserAgent, HARDCOVER_USER_AGENT)
                    setBody(
                        TextContent(
                            hardcoverJson.encodeToString(GraphQlRequest(query = query, variables = variables)),
                            ContentType.Application.Json,
                        ),
                    )
                }
            when {
                response.status == HttpStatusCode.Unauthorized -> unauthorized
                !response.status.isSuccess() -> unavailable("$label ${response.status}")
                else -> onSuccess(response.bodyAsText())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unavailable(e.message ?: e::class.simpleName.orEmpty())
        }

    private companion object {
        const val ME_QUERY = "{ me { id username } }"
        const val BOOK_FIELDS = "id title rating ratings_count contributions(limit:1){ author { name } }"
        const val EDITION_BY_ASIN_QUERY =
            "query(\$asin:String!){ editions(where:{asin:{_eq:\$asin}}, limit:1){ book { $BOOK_FIELDS } } }"
        const val EDITION_BY_ISBN_QUERY =
            "query(\$isbn:String!){ editions(where:{isbn_13:{_eq:\$isbn}}, limit:1){ book { $BOOK_FIELDS } } }"
        const val BOOKS_BY_TITLE_QUERY =
            "query(\$title:String!){ books(where:{title:{_eq:\$title}}, " +
                "order_by:{ratings_count:desc}, limit:5){ $BOOK_FIELDS } }"
    }
}

@Serializable
private data class GraphQlRequest(
    @SerialName("query") val query: String,
    @SerialName("variables") val variables: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class MeResponse(
    @SerialName("data") val data: MeData? = null,
)

@Serializable
private data class MeData(
    @SerialName("me") val me: List<MeWire> = emptyList(),
)

@Serializable
private data class MeWire(
    @SerialName("id") val id: Long,
    @SerialName("username") val username: String,
)

@Serializable
private data class EditionsResponse(
    @SerialName("data") val data: EditionsData? = null,
)

@Serializable
private data class EditionsData(
    @SerialName("editions") val editions: List<EditionWire> = emptyList(),
)

@Serializable
private data class EditionWire(
    @SerialName("book") val book: BookWire? = null,
)

@Serializable
private data class BooksResponse(
    @SerialName("data") val data: BooksData? = null,
)

@Serializable
private data class BooksData(
    @SerialName("books") val books: List<BookWire> = emptyList(),
)

@Serializable
private data class BookWire(
    @SerialName("title") val title: String,
    @SerialName("rating") val rating: Double? = null,
    @SerialName("ratings_count") val ratingsCount: Int = 0,
    @SerialName("contributions") val contributions: List<ContributionWire> = emptyList(),
) {
    fun toCandidate() =
        HardcoverBookCandidate(
            title = title,
            author = contributions.firstOrNull()?.author?.name,
            average = rating.takeIf { ratingsCount > 0 },
            count = ratingsCount,
        )
}

@Serializable
private data class ContributionWire(
    @SerialName("author") val author: AuthorWire? = null,
)

@Serializable
private data class AuthorWire(
    @SerialName("name") val name: String? = null,
)
