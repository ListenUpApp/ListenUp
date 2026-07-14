package com.calypsan.listenup.server.metadata.audnexus

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
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

private val logger = loggerFor<AudnexusClient>()

/**
 * Ktor-backed HTTP adapter for the Audnexus aggregator API (`api.audnex.us`).
 *
 * Audnexus is a free, community-run catalog that fills the gaps Audible's JSON API
 * leaves — author profiles (bio + photo), catalog-verified chapters, and typed
 * genres/tags. Every public method:
 * 1. Awaits the [rateLimiter] — a courtesy to the shared community service.
 * 2. Issues a GET to the endpoint on [baseUrl], passing the [region] query param.
 * 3. Maps the response to an [AppResult]:
 *    - 200 → decode JSON → [AppResult.Success]
 *    - 404 → `Success(null)` (a catalog miss, not a failure)
 *    - 429 → [MetadataError.ExternalRateLimited]
 *    - 5xx / network failure → [MetadataError.ExternalUnavailable]
 *    - decode failure → [MetadataError.Malformed]
 *
 * [baseUrl] is an operator override (`LISTENUP_AUDNEXUS_URL`): Audnexus is
 * open-source and self-hostable, so an operator can point ListenUp at their own
 * mirror — the never-strand rule at the provider edge. Defaults to the public host.
 *
 * [CancellationException] is never swallowed — it is always rethrown.
 */
internal class AudnexusClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val rateLimiter: AudnexusRateLimiter = AudnexusRateLimiter(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AudnexusApi {
    override suspend fun getBook(
        asin: String,
        region: String,
    ): AppResult<AudnexusBook?> = apiGet("/books/$asin", region) { body -> json.decodeFromString<AudnexusBook>(body) }

    override suspend fun getChapters(
        asin: String,
        region: String,
    ): AppResult<AudnexusChapters?> =
        apiGet("/books/$asin/chapters", region) { body -> json.decodeFromString<AudnexusChapters>(body) }

    override suspend fun searchAuthors(
        name: String,
        region: String,
    ): AppResult<List<AudnexusAuthor>> =
        apiGet("/authors", region, extraParams = mapOf("name" to name)) { body ->
            json.decodeFromString<List<AudnexusAuthor>>(body)
        }.map { it ?: emptyList() }

    override suspend fun getAuthor(
        asin: String,
        region: String,
    ): AppResult<AudnexusAuthorProfile?> =
        apiGet("/authors/$asin", region) { body -> json.decodeFromString<AudnexusAuthorProfile>(body) }

    /**
     * Issues a GET to [path] on [baseUrl] with the `region` query param (plus any
     * [extraParams]), and maps the response to [AppResult<T?>] via [decode]. A 404
     * maps to `Success(null)` — a catalog miss, not a failure. [CancellationException]
     * is always rethrown; every other exception becomes a typed [MetadataError].
     */
    private suspend fun <T> apiGet(
        path: String,
        region: String,
        extraParams: Map<String, String> = emptyMap(),
        decode: (String) -> T,
    ): AppResult<T?> =
        try {
            rateLimiter.await()
            val response =
                httpClient.get("$baseUrl$path") {
                    header("Accept", "application/json")
                    header("User-Agent", "ListenUp/1.0")
                    parameter("region", region)
                    extraParams.forEach { (k, v) -> parameter(k, v) }
                }
            when (response.status) {
                HttpStatusCode.OK -> {
                    try {
                        AppResult.Success(decode(response.bodyAsText()))
                    } catch (e: SerializationException) {
                        AppResult.Failure(MetadataError.Malformed(debugInfo = e.message))
                    } catch (e: IllegalArgumentException) {
                        // kotlinx.serialization throws IAE for type mismatches in some cases.
                        AppResult.Failure(MetadataError.Malformed(debugInfo = e.message))
                    }
                }

                HttpStatusCode.NotFound -> {
                    AppResult.Success(null)
                }

                HttpStatusCode.TooManyRequests -> {
                    logger.warn { "Audnexus rate-limited: path=$path region=$region" }
                    AppResult.Failure(MetadataError.ExternalRateLimited())
                }

                else -> {
                    logger.warn { "Audnexus request failed: path=$path region=$region status=${response.status.value}" }
                    AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = "HTTP ${response.status.value}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Audnexus request errored: path=$path region=$region" }
            AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = e.message))
        }

    companion object {
        /** The public Audnexus host; overridden per-operator via `LISTENUP_AUDNEXUS_URL`. */
        const val DEFAULT_BASE_URL: String = "https://api.audnex.us"
    }
}
