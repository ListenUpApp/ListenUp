package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Ktor-backed HTTP adapter for the Audible catalog API.
 *
 * Every public method:
 * 1. Awaits the [rateLimiter] for the target [AudibleRegion].
 * 2. Issues a GET request to the appropriate `/1.0/` endpoint on Audible's
 *    regional API host (e.g. `api.audible.com`, `api.audible.co.uk`).
 * 3. Maps the response to an [AppResult]:
 *    - 200 → decode JSON → [AppResult.Success]
 *    - 404 → [MetadataError.NotFound]
 *    - 429 → [MetadataError.ExternalRateLimited]
 *    - 5xx → [MetadataError.ExternalUnavailable]
 *    - decode failure → [MetadataError.Malformed]
 *    - network failure → [MetadataError.ExternalUnavailable]
 *
 * URL paths and query-parameter shapes are ported from the Go reference at
 * `server/internal/metadata/audible/`. The `response_groups` values are the
 * authoritative set from that implementation.
 *
 * [CancellationException] is never swallowed — it is always rethrown.
 */
class AudibleClient(
    private val httpClient: HttpClient,
    private val rateLimiter: AudibleRateLimiter,
    private val json: Json,
) : AudibleApi {
    /**
     * Searches the Audible catalog.
     *
     * Endpoint: `GET /1.0/catalog/products`
     * Response: [RawSearchResponse]
     */
    override suspend fun search(region: AudibleRegion, params: SearchParams): AppResult<List<AudibleSearchResult>> {
        rateLimiter.await(region)
        return apiGet(region, "/1.0/catalog/products", buildMap {
            params.keywords?.let { put("keywords", it) }
            params.title?.let { put("title", it) }
            params.author?.let { put("author", it) }
            params.narrator?.let { put("narrator", it) }
            val limit = params.limit.coerceIn(1, SearchParams.MAX_NUM_RESULTS)
            put("num_results", limit.toString())
            put("response_groups", RESPONSE_GROUPS)
            put("image_sizes", IMAGE_SIZES)
            put("products_sort_by", "Relevance")
        }) { body ->
            json.decodeFromString<RawSearchResponse>(body)
                .products
                .map { it.toSearchResult() }
        }
    }

    /**
     * Fetches full metadata for a single audiobook by ASIN.
     *
     * Endpoint: `GET /1.0/catalog/products/{asin}`
     * Response: [RawBookResponse]
     */
    override suspend fun getBook(region: AudibleRegion, asin: String): AppResult<AudibleBook?> {
        rateLimiter.await(region)
        return apiGet(region, "/1.0/catalog/products/$asin", buildMap {
            put("response_groups", RESPONSE_GROUPS)
            put("image_sizes", IMAGE_SIZES)
        }) { body ->
            json.decodeFromString<RawBookResponse>(body).product?.toBook()
        }
    }

    /**
     * Fetches chapter information for an audiobook by ASIN.
     *
     * Endpoint: `GET /1.0/content/{asin}/metadata?response_groups=chapter_info`
     * Response: [RawChaptersResponse]
     */
    override suspend fun getChapters(region: AudibleRegion, asin: String): AppResult<List<AudibleChapter>> {
        rateLimiter.await(region)
        return apiGet(region, "/1.0/content/$asin/metadata", mapOf("response_groups" to "chapter_info")) { body ->
            json.decodeFromString<RawChaptersResponse>(body)
                .contentMetadata
                .chapterInfo
                .chapters
                .map { ch ->
                    AudibleChapter(
                        title = ch.title,
                        startMs = ch.startOffsetMs,
                        durationMs = ch.lengthMs,
                    )
                }
        }
    }

    // ─── Private infrastructure ───────────────────────────────────────────────

    /**
     * Issues a GET request to [path] on [region]'s API host, applies [queryParams],
     * and maps the response to [AppResult<T>] via [decode].
     *
     * [CancellationException] is always rethrown. All other exceptions are
     * mapped to a typed [MetadataError].
     */
    private suspend fun <T> apiGet(
        region: AudibleRegion,
        path: String,
        queryParams: Map<String, String>,
        decode: (String) -> T,
    ): AppResult<T> {
        return try {
            val response = httpClient.get("https://${region.apiHost}$path") {
                header("Accept", "application/json")
                header("User-Agent", "ListenUp/1.0")
                queryParams.forEach { (k, v) -> parameter(k, v) }
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
                HttpStatusCode.NotFound ->
                    AppResult.Failure(MetadataError.NotFound())
                HttpStatusCode.TooManyRequests ->
                    AppResult.Failure(MetadataError.ExternalRateLimited())
                else ->
                    if (response.status.value in 500..599) {
                        AppResult.Failure(
                            MetadataError.ExternalUnavailable(
                                debugInfo = "HTTP ${response.status.value}",
                            ),
                        )
                    } else {
                        AppResult.Failure(
                            MetadataError.ExternalUnavailable(
                                debugInfo = "HTTP ${response.status.value} (unexpected)",
                            ),
                        )
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = e.message))
        }
    }

    private companion object {
        /**
         * Standard response_groups for product requests.
         * Matches Go's `responseGroups()` in `client.go`.
         */
        const val RESPONSE_GROUPS =
            "contributors,product_desc,product_attrs,product_extended_attrs,media,rating,series,category_ladders"

        /**
         * Image size variants to request.
         * Matches Go's `imageSizes()` in `client.go`.
         */
        const val IMAGE_SIZES = "500,1024"
    }
}

// ─── Raw → Domain mappers ─────────────────────────────────────────────────────

private fun RawProduct.toSearchResult(): AudibleSearchResult {
    val (authors, narrators) = separateContributors(this.authors, this.narrators)
    return AudibleSearchResult(
        asin = asin,
        title = title,
        subtitle = subtitle,
        authors = authors,
        narrators = narrators,
        coverUrl = selectCoverUrl(productImages),
        runtimeMinutes = runtimeLengthMin,
        releaseDate = releaseDate,
    )
}

private fun RawProduct.toBook(): AudibleBook {
    val (authors, narrators) = separateContributors(this.authors, this.narrators)
    val rating = this.rating?.overallDistribution?.displayAverageRating?.value ?: 0f
    val ratingCount = this.rating?.overallDistribution?.numReviews ?: 0
    return AudibleBook(
        asin = asin,
        title = title,
        subtitle = subtitle,
        authors = authors,
        narrators = narrators,
        publisher = publisherName,
        releaseDate = releaseDate,
        runtimeMinutes = runtimeLengthMin,
        description = stripHtmlTags(merchandisingSummary),
        coverUrl = selectCoverUrl(productImages),
        series = this.series.map {
            AudibleSeriesEntry(asin = it.asin, name = it.title, position = it.sequence)
        },
        genres = extractGenres(categoryLadders),
        language = language,
        rating = rating,
        ratingCount = ratingCount,
    )
}

/**
 * Separates contributors by role. Go's API sometimes places narrators in the
 * `authors` array with `role = "narrator"`, so both arrays must be inspected.
 * Mirrors Go's `separateContributorsByRole` in `client.go`.
 */
private fun separateContributors(
    rawAuthors: List<RawContributor>,
    rawNarrators: List<RawContributor>,
): Pair<List<AudibleContributor>, List<AudibleContributor>> {
    val seen = mutableSetOf<String>()
    val authors = mutableListOf<AudibleContributor>()
    val narrators = mutableListOf<AudibleContributor>()

    fun key(c: RawContributor) = c.asin.ifEmpty { c.name }

    for (c in rawAuthors) {
        val k = key(c)
        if (seen.add(k)) {
            val contributor = AudibleContributor(asin = c.asin, name = c.name)
            if (c.role == "narrator") narrators += contributor else authors += contributor
        }
    }
    for (c in rawNarrators) {
        val k = key(c)
        if (seen.add(k)) {
            narrators += AudibleContributor(asin = c.asin, name = c.name)
        }
    }
    return authors to narrators
}

/** Picks the best cover URL from the product_images map; prefers 1024px. */
private fun selectCoverUrl(images: Map<String, String>): String =
    images["1024"]?.ifBlank { null }
        ?: images["500"]?.ifBlank { null }
        ?: images.values.firstOrNull()
        ?: ""

/** Extracts genre names from Audible's nested category-ladder structure. */
private fun extractGenres(ladders: List<RawCategoryLadder>): List<String> {
    val seen = mutableSetOf<String>()
    return ladders.flatMap { it.ladder }.mapNotNull { cat ->
        if (cat.name.isNotEmpty() && seen.add(cat.name)) cat.name else null
    }
}

/**
 * Removes HTML tags from Audible's `merchandising_summary` field.
 * Audible wraps descriptions in `<p>` tags; strip them for plain text.
 */
private fun stripHtmlTags(html: String): String =
    html.replace(Regex("<[^>]+>"), "").trim()
