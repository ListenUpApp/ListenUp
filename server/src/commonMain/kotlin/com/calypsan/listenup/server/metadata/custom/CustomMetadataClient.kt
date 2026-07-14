package com.calypsan.listenup.server.metadata.custom

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.logging.loggerFor
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val logger = loggerFor<CustomMetadataClient>()

/**
 * Ktor-backed HTTP adapter for one operator-declared custom metadata endpoint.
 *
 * Speaks the thin JSON contract documented in `CustomTypes` over the shared metadata
 * [HttpClient], paced by its own [rateLimiter]. Every method:
 * 1. Awaits the [rateLimiter].
 * 2. Issues a GET to the endpoint on [baseUrl], passing the lookup key params (`asin`,
 *    `title`, `author`) it knows plus `region`, skipping blanks.
 * 3. Maps the response to an [AppResult], identically to the built-in provider clients:
 *    - 200 → decode JSON → [AppResult.Success]
 *    - 404 → `Success(null)` (a catalog miss, not a failure)
 *    - 429 → [MetadataError.ExternalRateLimited]
 *    - 5xx / network failure → [MetadataError.ExternalUnavailable]
 *    - decode failure → [MetadataError.Malformed]
 *
 * Authentication is the operator's responsibility — front the endpoint with whatever the
 * hosting setup provides (a reverse-proxy token, network ACL, or a token baked into the
 * URL). Keeping the client auth-free keeps the config surface a single `name=baseUrl` line.
 *
 * [CancellationException] is never swallowed — it is always rethrown.
 */
internal class CustomMetadataClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val baseUrl: String,
    private val rateLimiter: CustomRateLimiter = CustomRateLimiter(),
) {
    suspend fun getBook(
        asin: String?,
        title: String?,
        author: String?,
        region: String,
    ): AppResult<CustomBookJson?> =
        apiGet("/book", asin, title, author, region) { json.decodeFromString<CustomBookJson>(it) }

    suspend fun getCharacters(
        asin: String?,
        title: String?,
        region: String,
    ): AppResult<List<CustomCharacterJson>?> =
        apiGet(
            "/characters",
            asin,
            title,
            author = null,
            region,
        ) { json.decodeFromString<List<CustomCharacterJson>>(it) }

    suspend fun getCovers(
        asin: String?,
        title: String?,
        author: String?,
        region: String,
    ): AppResult<List<CustomCoverJson>?> =
        apiGet("/cover", asin, title, author, region) { json.decodeFromString<List<CustomCoverJson>>(it) }

    suspend fun getGenres(
        asin: String?,
        title: String?,
        region: String,
    ): AppResult<List<CustomGenreJson>?> =
        apiGet("/genres", asin, title, author = null, region) { json.decodeFromString<List<CustomGenreJson>>(it) }

    suspend fun getSeries(
        asin: String?,
        title: String?,
        region: String,
    ): AppResult<List<CustomSeriesJson>?> =
        apiGet("/series", asin, title, author = null, region) { json.decodeFromString<List<CustomSeriesJson>>(it) }

    /**
     * Issues a GET to [path] on [baseUrl] with the non-blank lookup params plus `region`,
     * mapping the response to [AppResult<T?>] via [decode]. A 404 maps to `Success(null)`.
     * [CancellationException] is always rethrown; every other failure becomes a typed
     * [MetadataError].
     */
    private suspend fun <T> apiGet(
        path: String,
        asin: String?,
        title: String?,
        author: String?,
        region: String,
        decode: (String) -> T,
    ): AppResult<T?> =
        try {
            rateLimiter.await()
            val response =
                httpClient.get("$baseUrl$path") {
                    header("Accept", "application/json")
                    header("User-Agent", "ListenUp/1.0")
                    parameter("region", region)
                    asin?.takeIf { it.isNotBlank() }?.let { parameter("asin", it) }
                    title?.takeIf { it.isNotBlank() }?.let { parameter("title", it) }
                    author?.takeIf { it.isNotBlank() }?.let { parameter("author", it) }
                }
            when (response.status) {
                HttpStatusCode.OK -> {
                    try {
                        AppResult.Success(decode(response.bodyAsText()))
                    } catch (e: SerializationException) {
                        AppResult.Failure(MetadataError.Malformed(debugInfo = e.message))
                    } catch (e: IllegalArgumentException) {
                        AppResult.Failure(MetadataError.Malformed(debugInfo = e.message))
                    }
                }

                HttpStatusCode.NotFound -> {
                    AppResult.Success(null)
                }

                HttpStatusCode.TooManyRequests -> {
                    logger.warn { "Custom provider rate-limited: base=$baseUrl path=$path region=$region" }
                    AppResult.Failure(MetadataError.ExternalRateLimited())
                }

                else -> {
                    logger.warn {
                        "Custom provider request failed: base=$baseUrl path=$path status=${response.status.value}"
                    }
                    AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = "HTTP ${response.status.value}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Custom provider request errored: base=$baseUrl path=$path region=$region" }
            AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = e.message))
        }
}
