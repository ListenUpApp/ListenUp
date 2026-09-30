package com.calypsan.listenup.server.hardcover

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
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

    /**
     * Sends one GraphQL request and classifies the answer ([classifyHardcoverResponse]). Never throws
     * anything but [CancellationException]: an unreachable Hardcover is [HardcoverCall.Failed].
     */
    internal suspend fun call(
        accessToken: String,
        query: String,
        variables: JsonObject,
        label: String,
    ): HardcoverCall<String> =
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
            classifyHardcoverResponse(
                status = response.status,
                retryAfter = response.headers[HttpHeaders.RetryAfter],
                wwwAuthenticate = response.headers[HttpHeaders.WWWAuthenticate],
                body = response.bodyAsText(),
                label = label,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HardcoverCall.Failed("$label: ${e.message ?: e::class.simpleName.orEmpty()}")
        }

    /** [call], then [decode] an [HardcoverCall.Ok] body; a body that won't decode is [HardcoverCall.Failed]. */
    internal suspend fun <T> fetch(
        accessToken: String,
        query: String,
        variables: JsonObject,
        label: String,
        decode: (String) -> T,
    ): HardcoverCall<T> {
        val body = call(accessToken, query, variables, label).valueOr { return it }
        return try {
            HardcoverCall.Ok(decode(body))
        } catch (e: IllegalArgumentException) {
            HardcoverCall.Failed("$label: undecodable answer (${e.message})")
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
        when (val result = fetch(accessToken, query, variables, label, onSuccess)) {
            is HardcoverCall.Ok -> result.value
            HardcoverCall.Unauthorized -> unauthorized
            is HardcoverCall.MissingScope -> unavailable("$label: missing scope ${result.scope}")
            is HardcoverCall.Throttled -> unavailable("$label: throttled")
            is HardcoverCall.Failed -> unavailable(result.detail)
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
