package com.calypsan.listenup.server.goodreads

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/** Outcome of one Goodreads page fetch. */
sealed interface GoodreadsFetch {
    /** The page's [html]. */
    data class Page(
        val html: String,
    ) : GoodreadsFetch

    /** No such page: Goodreads does not know the ISBN or the book. */
    data object NotFound : GoodreadsFetch

    /** Goodreads answered, but declined to serve the page ([status] 403, 429 or 503). */
    data class Refused(
        val status: Int,
    ) : GoodreadsFetch

    /** Goodreads could not be reached, or failed ([detail] says how). */
    data class Unreachable(
        val detail: String,
    ) : GoodreadsFetch
}

/**
 * Fetches public Goodreads pages: a book page by ISBN (Goodreads redirects `/book/isbn/{isbn}` to the
 * book, and the client follows it), a book page by its site path, and the title search. Pages are read
 * as text: [GoodreadsPages] makes sense of them. Every failure is a typed [GoodreadsFetch] — a
 * transport error never escapes, and [CancellationException] is rethrown.
 *
 * Asks as a browser would ([USER_AGENT]): Goodreads serves its public pages to browsers. It is never
 * asked twice, or with other credentials, when it refuses; a refusal is reported as one.
 */
class GoodreadsClient(
    private val http: HttpClient,
    private val baseUrl: String = GOODREADS_BASE_URL,
) {
    /** The book page for [isbn]. */
    suspend fun bookPageByIsbn(isbn: String): GoodreadsFetch = fetch("/book/isbn/${isbn.encodeURLPathPart()}")

    /** The book page at site-relative [bookPath], as [GoodreadsPages.searchResults] names it. */
    suspend fun bookPage(bookPath: String): GoodreadsFetch = fetch(bookPath)

    /** The search results for [query]. */
    suspend fun search(query: String): GoodreadsFetch = fetch("/search") { parameter("q", query) }

    private suspend fun fetch(
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): GoodreadsFetch =
        try {
            val response =
                http.get("$baseUrl$path") {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Accept, "text/html")
                    configure()
                }
            when {
                response.status == HttpStatusCode.NotFound -> GoodreadsFetch.NotFound
                response.status.value in REFUSALS -> GoodreadsFetch.Refused(response.status.value)
                !response.status.isSuccess() -> GoodreadsFetch.Unreachable("$path ${response.status}")
                else -> GoodreadsFetch.Page(response.bodyAsText())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GoodreadsFetch.Unreachable(e.message ?: e::class.simpleName.orEmpty())
        }

    /** Goodreads' host, and the browser identity its pages are asked for with. */
    companion object {
        const val GOODREADS_BASE_URL = "https://www.goodreads.com"

        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private val REFUSALS =
            setOf(
                HttpStatusCode.Forbidden.value,
                HttpStatusCode.TooManyRequests.value,
                HttpStatusCode.ServiceUnavailable.value,
            )
    }
}
