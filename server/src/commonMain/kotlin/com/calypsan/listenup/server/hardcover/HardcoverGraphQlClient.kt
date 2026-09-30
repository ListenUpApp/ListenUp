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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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

/**
 * One book in Hardcover's catalog. [average] is null while [count] is 0. [defaultAudioEditionId] is
 * the edition Hardcover shows for listeners, when it names one.
 */
data class HardcoverCatalogBook(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val average: Double?,
    val count: Int,
    val releaseYear: Int?,
    val defaultAudioEditionId: Long?,
) {
    /** The first-credited author, as the rating lookup has always read it. */
    val author: String? get() = authors.firstOrNull()
}

/** An edition found by an exact identifier, with its book. */
data class HardcoverEditionHit(
    val editionId: Long,
    val isAudiobook: Boolean,
    val book: HardcoverCatalogBook,
)

/** One book a Hardcover `search` returned, in Hardcover's own relevance order. */
data class HardcoverSearchHit(
    val bookId: Long,
    val title: String,
    val authors: List<String>,
    val releaseYear: Int?,
)

/** Outcome of [HardcoverGraphQlClient.booksByTitle]. */
sealed interface HardcoverCandidatesResult {
    /** Books titled exactly as asked, most-rated first; possibly empty. */
    data class Found(
        val candidates: List<HardcoverCatalogBook>,
    ) : HardcoverCandidatesResult

    /** Hardcover rejected the token. */
    data object Unauthorized : HardcoverCandidatesResult

    /** Transient failure. */
    data class Unavailable(
        val detail: String,
    ) : HardcoverCandidatesResult
}

/**
 * Hardcover's GraphQL API: `me`, the rating lookups, and the catalog lookups that matching and
 * manual linking read (edition by ASIN or ISBN, books by title or id, and search). Every failure is a
 * typed result — a transport error never escapes, and [CancellationException] is rethrown.
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
    ): HardcoverRatingResult = editionByAsin(accessToken, asin).toRatingResult()

    /** How Hardcover's readers rate the book behind the edition with [isbn]. */
    suspend fun editionRatingByIsbn(
        accessToken: String,
        isbn: String,
    ): HardcoverRatingResult = editionByIsbn(accessToken, isbn).toRatingResult()

    /** Up to five books titled exactly [title], most-rated first, for the caller to match by author. */
    suspend fun booksByTitle(
        accessToken: String,
        title: String,
    ): HardcoverCandidatesResult =
        when (val result = booksTitled(accessToken, title)) {
            is HardcoverCall.Ok -> HardcoverCandidatesResult.Found(result.value)
            HardcoverCall.Unauthorized -> HardcoverCandidatesResult.Unauthorized
            is HardcoverCall.MissingScope -> HardcoverCandidatesResult.Unavailable("booksTitled: missing scope ${result.scope}")
            is HardcoverCall.Throttled -> HardcoverCandidatesResult.Unavailable("booksTitled: throttled")
            is HardcoverCall.Failed -> HardcoverCandidatesResult.Unavailable(result.detail)
        }

    /** The edition with Audible [asin] and its book, or null when Hardcover has none. */
    suspend fun editionByAsin(
        accessToken: String,
        asin: String,
    ): HardcoverCall<HardcoverEditionHit?> =
        fetch(accessToken, EDITION_BY_ASIN_QUERY, buildJsonObject { put("asin", asin) }, "editionByAsin", ::firstEdition)

    /** The edition with [isbn] as its ISBN-13 or ISBN-10, and its book, or null when Hardcover has none. */
    suspend fun editionByIsbn(
        accessToken: String,
        isbn: String,
    ): HardcoverCall<HardcoverEditionHit?> =
        fetch(accessToken, EDITION_BY_ISBN_QUERY, buildJsonObject { put("isbn", isbn) }, "editionByIsbn", ::firstEdition)

    /** Up to five books titled exactly [title], most-rated first. */
    suspend fun booksTitled(
        accessToken: String,
        title: String,
    ): HardcoverCall<List<HardcoverCatalogBook>> =
        fetch(accessToken, BOOKS_BY_TITLE_QUERY, buildJsonObject { put("title", title) }, "booksTitled", ::books)

    /** Books Hardcover's search finds for [query], best first. Costs the request's one `search`. */
    suspend fun searchBooks(
        accessToken: String,
        query: String,
    ): HardcoverCall<List<HardcoverSearchHit>> =
        fetch(accessToken, SEARCH_QUERY, buildJsonObject { put("query", query) }, "searchBooks") { body ->
            hardcoverJson
                .decodeFromString<SearchResponse>(body)
                .data
                ?.search
                ?.results
                ?.hits
                .orEmpty()
                .mapNotNull { hit ->
                    hit.document.id.content.toLongOrNull()?.let { id ->
                        HardcoverSearchHit(id, hit.document.title, hit.document.authorNames, hit.document.releaseYear)
                    }
                }
        }

    /** The books with these [ids], in no particular order. */
    suspend fun booksByIds(
        accessToken: String,
        ids: List<Long>,
    ): HardcoverCall<List<HardcoverCatalogBook>> =
        fetch(
            accessToken,
            BOOKS_BY_IDS_QUERY,
            buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } },
            "booksByIds",
            ::books,
        )

    private fun firstEdition(body: String): HardcoverEditionHit? =
        hardcoverJson
            .decodeFromString<EditionsResponse>(body)
            .data
            ?.editions
            ?.firstOrNull()
            ?.let { edition ->
                edition.book?.let { HardcoverEditionHit(edition.id, edition.readingFormatId == AUDIOBOOK_READING_FORMAT, it.toCatalogBook()) }
            }

    private fun books(body: String): List<HardcoverCatalogBook> =
        hardcoverJson
            .decodeFromString<BooksResponse>(body)
            .data
            ?.books
            .orEmpty()
            .map { it.toCatalogBook() }

    private fun HardcoverCall<HardcoverEditionHit?>.toRatingResult(): HardcoverRatingResult =
        when (this) {
            is HardcoverCall.Ok -> {
                val book = value?.book
                val average = book?.average
                if (book == null || average == null || book.count <= 0) {
                    HardcoverRatingResult.NotFound
                } else {
                    HardcoverRatingResult.Found(average, book.count, book.title, book.author)
                }
            }

            HardcoverCall.Unauthorized -> HardcoverRatingResult.Unauthorized
            is HardcoverCall.MissingScope -> HardcoverRatingResult.Unavailable("edition rating: missing scope $scope")
            is HardcoverCall.Throttled -> HardcoverRatingResult.Unavailable("edition rating: throttled")
            is HardcoverCall.Failed -> HardcoverRatingResult.Unavailable(detail)
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
        /** Hardcover's `reading_formats` id for audiobooks ("Listened"), confirmed in Task 1 Step 3. */
        const val AUDIOBOOK_READING_FORMAT = 2
        const val ME_QUERY = "{ me { id username } }"
        const val BOOK_FIELDS =
            "id title rating ratings_count release_year default_audio_edition_id contributions(limit:5){ author { name } }"
        const val EDITION_FIELDS = "id reading_format_id book { $BOOK_FIELDS }"
        const val EDITION_BY_ASIN_QUERY =
            "query(\$asin:String!){ editions(where:{asin:{_eq:\$asin}}, limit:1){ $EDITION_FIELDS } }"
        const val EDITION_BY_ISBN_QUERY =
            "query(\$isbn:String!){ editions(where:{_or:[{isbn_13:{_eq:\$isbn}},{isbn_10:{_eq:\$isbn}}]}, limit:1){ $EDITION_FIELDS } }"
        const val BOOKS_BY_TITLE_QUERY =
            "query(\$title:String!){ books(where:{title:{_eq:\$title}}, " +
                "order_by:{ratings_count:desc}, limit:5){ $BOOK_FIELDS } }"
        const val SEARCH_QUERY =
            "query(\$query:String!){ search(query:\$query, query_type:\"Book\", per_page:10, page:1){ results } }"
        const val BOOKS_BY_IDS_QUERY = "query(\$ids:[Int!]!){ books(where:{id:{_in:\$ids}}){ $BOOK_FIELDS } }"
    }
}
