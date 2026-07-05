package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.logging.loggerFor
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val logger = loggerFor<AudibleClient>()

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
 * URL paths, query-parameter shapes, and the `response_groups` values target
 * Audible's regional `/1.0/` catalog endpoints.
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
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> {
        rateLimiter.await(region)
        return apiGet(
            region,
            "/1.0/catalog/products",
            buildMap {
                params.keywords?.let { put("keywords", it) }
                params.title?.let { put("title", it) }
                params.author?.let { put("author", it) }
                params.narrator?.let { put("narrator", it) }
                val limit = params.limit.coerceIn(1, SearchParams.MAX_NUM_RESULTS)
                put("num_results", limit.toString())
                put(PARAM_RESPONSE_GROUPS, RESPONSE_GROUPS)
                put("image_sizes", IMAGE_SIZES)
                put("products_sort_by", "Relevance")
            },
        ) { body ->
            json
                .decodeFromString<RawSearchResponse>(body)
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
    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> {
        rateLimiter.await(region)
        return apiGet(
            region,
            "/1.0/catalog/products/$asin",
            buildMap {
                put(PARAM_RESPONSE_GROUPS, RESPONSE_GROUPS)
                put("image_sizes", IMAGE_SIZES)
            },
        ) { body ->
            json.decodeFromString<RawBookResponse>(body).product?.toBook()
        }
    }

    /**
     * Fetches chapter information for an audiobook by ASIN.
     *
     * Endpoint: `GET /1.0/content/{asin}/metadata?response_groups=chapter_info`
     * Response: [RawChaptersResponse]
     */
    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> {
        rateLimiter.await(region)
        return apiGet(region, "/1.0/content/$asin/metadata", mapOf(PARAM_RESPONSE_GROUPS to "chapter_info")) { body ->
            json
                .decodeFromString<RawChaptersResponse>(body)
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

    /**
     * Fetches a contributor profile by scraping the Audible author page.
     *
     * Endpoint: `GET https://www.audible.{tld}/author/x/{asin}`
     *
     * Audible's catalog API no longer returns contributor images or biographies,
     * so the author web page is the only reliable source.
     * A placeholder name (`x`) is used in the URL because Audible redirects to
     * the canonical slug regardless.
     *
     * The page is scraped with lightweight regex extraction:
     *  - Name: `<h1 class="bc-heading...">` text content
     *  - Biography: `<div class="bc-expander-content">` text content
     *  - Image: `og:image` meta tag `content` attribute (falls back to
     *    `author-image-outline` img `src`), filtered for the placeholder
     *    image Audible uses when no author photo is available.
     */
    override suspend fun getContributor(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleContributorProfile?> {
        rateLimiter.await(region)
        return webGet(region, "/author/x/$asin") { body ->
            parseContributorProfile(body, asin)
        }
    }

    override suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ): AppResult<List<AudibleContributorProfile>> {
        rateLimiter.await(region)
        // webGet returns AppResult<List<AudibleContributorProfile>?> — the search page always
        // returns 200, so null is not expected; map it to an empty list as a safe fallback.
        val raw: AppResult<List<AudibleContributorProfile>?> =
            webGet(region, "/search", queryParams = mapOf("searchAuthor" to name)) { body ->
                parseContributorSearch(body)
            }
        return raw.map { it ?: emptyList() }
    }

    /**
     * Scrapes typed topic tags from the Audible product page by ASIN.
     *
     * Endpoint: `GET https://www.audible.{tld}/pd/{asin}`
     *
     * Best-effort and known-degraded: a 404 maps the `null` body to an empty list, and any transport
     * failure — including Audible's edge bot-block, which currently returns a uniform 503 for the
     * `www` host (Finding #18b) — is returned as a typed [MetadataError] that the caller flattens to
     * an empty list, so moods/tags are simply absent rather than fatal. Failures are not logged per
     * request here; [webGet] logs the www scrape's known 503 at debug level to avoid log spam.
     */
    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> {
        rateLimiter.await(region)
        return webGet(region, "/pd/$asin", failOnGeoRedirect = true) { body ->
            parseProductTags(body)
        }.map { it ?: emptyList() }
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
    ): AppResult<T> =
        try {
            val response =
                httpClient.get("https://${region.apiHost}$path") {
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

                HttpStatusCode.NotFound -> {
                    AppResult.Failure(MetadataError.NotFound())
                }

                HttpStatusCode.TooManyRequests -> {
                    logger.warn { "Audible API rate-limited: region=$region path=$path" }
                    AppResult.Failure(MetadataError.ExternalRateLimited())
                }

                else -> {
                    if (response.status.value in 500..599) {
                        logger.warn {
                            "Audible API server error: region=$region path=$path status=${response.status.value}"
                        }
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Audible API request failed: region=$region path=$path" }
            AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = e.message))
        }

    /**
     * Issues a GET request to [path] on [region]'s **website** host
     * (`www.audible.{tld}`) with browser-style headers, and maps the response
     * body via [decode].
     *
     * Used by [getContributor] and [searchContributors] which scrape Audible
     * web pages rather than calling the JSON API. Optional [queryParams] are
     * appended to the URL. [CancellationException] is always rethrown.
     */
    private suspend fun <T> webGet(
        region: AudibleRegion,
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        failOnGeoRedirect: Boolean = false,
        decode: (String) -> T?,
    ): AppResult<T?> =
        try {
            val response =
                httpClient.get("https://${region.webHost}$path") {
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    header("Accept-Language", "en-US,en;q=0.9")
                    header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    )
                    // Storefront locale cookie — forces the correct regional catalog. (It no longer
                    // clears Audible's 503: the www host now bot-blocks datacenter clients regardless
                    // of the cookie — Finding #18b.)
                    header("Cookie", region.localeCookie())
                    queryParams.forEach { (k, v) -> parameter(k, v) }
                }
            when {
                failOnGeoRedirect && response.geoRedirectedAwayFrom(region) -> {
                    AppResult.Failure(
                        MetadataError.ExternalUnavailable(
                            debugInfo =
                                "Audible IP-redirected the $region storefront to " +
                                    "${response.call.request.url.host} — region unavailable from this server's location",
                        ),
                    )
                }

                response.status == HttpStatusCode.OK -> {
                    AppResult.Success(decode(response.bodyAsText()))
                }

                response.status == HttpStatusCode.NotFound -> {
                    AppResult.Success(null)
                }

                response.status == HttpStatusCode.TooManyRequests -> {
                    logger.warn { "Audible web request rate-limited: region=$region path=$path" }
                    AppResult.Failure(MetadataError.ExternalRateLimited())
                }

                else -> {
                    // The www host is known-degraded (Audible edge bot-blocks datacenter clients with a
                    // uniform 503 — Finding #18b); log at debug so a per-request 5xx doesn't spam WARN.
                    logger.debug {
                        "Audible web request server error: region=$region path=$path status=${response.status.value}"
                    }
                    AppResult.Failure(
                        MetadataError.ExternalUnavailable(debugInfo = "HTTP ${response.status.value}"),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Audible web request failed: region=$region path=$path" }
            AppResult.Failure(MetadataError.ExternalUnavailable(debugInfo = e.message))
        }

    /**
     * True when Audible redirected this web-host request off [region]'s storefront — i.e. the final
     * URL is on a different host than [region]'s [webHost], or carries Audible's `ipRedirectFrom`
     * IP-geo marker. Audible gates each regional storefront by request IP: a server outside the
     * region is bounced to its own country's store (often the homepage), so the `/pd` product page
     * never loads. A same-host canonical-slug redirect (e.g. `/pd/{asin}` → `/pd/Title/{asin}`) is
     * NOT flagged.
     */
    private fun HttpResponse.geoRedirectedAwayFrom(region: AudibleRegion): Boolean {
        val finalUrl = call.request.url
        return !finalUrl.host.equals(region.webHost, ignoreCase = true) ||
            finalUrl.parameters.contains("ipRedirectFrom")
    }

    private companion object {
        /** Query parameter key for Audible response groups. */
        const val PARAM_RESPONSE_GROUPS = "response_groups"

        /**
         * Standard response_groups for product requests.
         */
        const val RESPONSE_GROUPS =
            "contributors,product_desc,product_attrs,product_extended_attrs,media,rating,series,category_ladders"

        /**
         * Image size variants to request.
         */
        const val IMAGE_SIZES = "500,1024"
    }
}

// ─── Contributor HTML scraping ────────────────────────────────────────────────

/**
 * Extracts [AudibleContributorProfile] from an Audible author page HTML body.
 *
 * Returns `null` when the page contains no `<h1 class="bc-heading">` element —
 * Audible serves a generic page for unknown ASINs rather than a 404.
 *
 * Uses regex extraction rather than a full HTML parser to avoid adding a
 * non-Kotlin-native dependency. The patterns are robust to Audible's typical
 * minification and attribute ordering because they target unique class names
 * and meta-tag attributes rather than tag structure.
 */
internal fun parseContributorProfile(
    html: String,
    asin: String,
): AudibleContributorProfile? {
    // Name: first <h1> with class containing "bc-heading"
    val name = extractH1Text(html, "bc-heading") ?: return null

    // Biography: text inside the first element with class "bc-expander-content"
    val biography = extractElementText(html, "bc-expander-content") ?: ""

    // Image: prefer og:image meta tag; fall back to author-image-outline img src. The
    // author-image-outline thumbnail is a tiny 120px rendition — upsize it.
    val imageUrl =
        upsizeAmazonImage(
            extractOgImage(html)?.takeUnless { it.contains(PLACEHOLDER_IMAGE_FRAGMENT) }
                ?: extractImgSrc(html, "author-image-outline") ?: "",
        )

    return AudibleContributorProfile(asin = asin, name = name, biography = biography, imageUrl = imageUrl)
}

private const val PLACEHOLDER_IMAGE_FRAGMENT = "Facebook_Placement"

/** Target width (px) requested from Amazon's image server for contributor photos. */
private const val AUTHOR_IMAGE_TARGET_PX = 600

/**
 * Rewrites an Amazon image URL's size operator to request a larger rendition. Audible's
 * `author-image-outline` photo is served as a 120px thumbnail (e.g.
 * `…/{id}.__01_SX120_CR0,0,120,120__.jpg`); Amazon's image server honours a `._SX{n}_` operator,
 * so this swaps whichever operator block is present for `._SX600_` to fetch a sharp avatar.
 * Non-Amazon URLs (and URLs with no operator block) are returned unchanged.
 */
private fun upsizeAmazonImage(url: String): String {
    if (!url.contains("media-amazon", ignoreCase = true) && !url.contains("images-amazon", ignoreCase = true)) {
        return url
    }
    // Matches the `.<__…__|_…_>.ext` size-operator block (the underscore count is back-referenced
    // so `.__01_SX120_CR0,0,120,120__.jpg` and `._SX120_.jpg` both collapse to `._SX600_.ext`).
    val operatorBlock = Regex("""\.(_{1,2})[^./]*\1\.(jpe?g|png)$""", RegexOption.IGNORE_CASE)
    return operatorBlock.replace(url, "._SX${AUTHOR_IMAGE_TARGET_PX}_.$2")
}

/** Extracts the trimmed text content of the first `<h1 class="...{cssClass}...">` element. */
private fun extractH1Text(
    html: String,
    cssClass: String,
): String? {
    val pattern = Regex("""<h1[^>]*class="[^"]*\b$cssClass\b[^"]*"[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
    val raw = pattern.find(html)?.groupValues?.get(1) ?: return null
    return stripHtmlEntities(raw).takeIf { it.isNotBlank() }
}

/** Extracts the trimmed text content of the first element with [cssClass] as a class. */
private fun extractElementText(
    html: String,
    cssClass: String,
): String? {
    val pattern = Regex("""class="[^"]*\b$cssClass\b[^"]*"[^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
    val raw = pattern.find(html)?.groupValues?.get(1) ?: return null
    return stripHtmlEntities(raw).trim().takeIf { it.isNotBlank() }
}

/** Extracts the `content` attribute of `<meta property="og:image" ...>`. */
private fun extractOgImage(html: String): String? {
    val pattern = Regex("""<meta[^>]*property="og:image"[^>]*content="([^"]+)"""")
    return pattern.find(html)?.groupValues?.get(1)
        ?: Regex("""<meta[^>]*content="([^"]+)"[^>]*property="og:image"""").find(html)?.groupValues?.get(1)
}

/** Extracts the `src` attribute of the first `<img class="...{cssClass}...">`. */
private fun extractImgSrc(
    html: String,
    cssClass: String,
): String? {
    val pattern = Regex("""<img[^>]*class="[^"]*\b$cssClass\b[^"]*"[^>]*src="([^"]+)"""")
    return pattern.find(html)?.groupValues?.get(1)
        ?: Regex("""<img[^>]*src="([^"]+)"[^>]*class="[^"]*\b$cssClass\b[^"]*"""").find(html)?.groupValues?.get(1)
}

// ─── Contributor search HTML scraping ────────────────────────────────────────

/**
 * Extracts [AudibleContributorProfile] list from an Audible search-by-author
 * page (`/search?searchAuthor=...`).
 *
 * The page includes author links of the form `/author/{slug}/{ASIN}`. Each
 * unique ASIN is deduplicated; the same author often appears across multiple
 * product listings on the page.
 *
 * Returns an empty list when no author links match — not null — because the
 * search endpoint always returns HTTP 200.
 *
 * Uses regex extraction matching the approach in [parseContributorProfile]:
 * no full HTML parser to avoid a non-Kotlin-native dependency.
 */
internal fun parseContributorSearch(html: String): List<AudibleContributorProfile> {
    // Author links are `href="/author/{slug}/{ASIN}?{tracking}"` — Audible always appends tracking
    // query params (ref, pf_rd_*, plink, …) after the ASIN. The pattern must therefore tolerate
    // anything up to the closing quote after the ASIN; requiring a quote *immediately* after it
    // (the previous behaviour) matched nothing, so every contributor search returned empty.
    // The anchor's inner text is the contributor name.
    val anchorPattern =
        Regex(
            """href="/author/[^/]+/([A-Z0-9]+)[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    val seen = mutableSetOf<String>()
    val results = mutableListOf<AudibleContributorProfile>()

    anchorPattern.findAll(html).forEach { match ->
        val asin = match.groupValues[1]
        if (!seen.add(asin)) return@forEach // an author repeats across product listings — dedupe

        val name = stripHtmlEntities(match.groupValues[2]).trim().takeIf { it.isNotBlank() } ?: return@forEach
        results += AudibleContributorProfile(asin = asin, name = name, biography = "", imageUrl = "")
    }

    return results
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
    val rating =
        this.rating
            ?.overallDistribution
            ?.displayAverageRating
            ?.value ?: 0f
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
        series =
            this.series.map {
                AudibleSeriesEntry(asin = it.asin, name = it.title, position = it.sequence)
            },
        genres = extractGenres(categoryLadders),
        genreLadders = extractGenreLadders(categoryLadders),
        language = language,
        rating = rating,
        ratingCount = ratingCount,
    )
}

/**
 * Separates contributors by role. Audible's API sometimes places narrators in the
 * `authors` array with `role = "narrator"`, so both arrays must be inspected.
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
 * Preserves Audible's category ladders root → leaf — one inner list of rung
 * names per ladder, blank rungs dropped, empty ladders omitted. Unlike
 * [extractGenres] (which flattens + dedups), this retains the hierarchy so the
 * match-apply path can nest genres (Fiction → Fantasy → LitRPG).
 */
private fun extractGenreLadders(ladders: List<RawCategoryLadder>): List<List<String>> =
    ladders
        .map { ladder -> ladder.ladder.map { it.name }.filter { it.isNotEmpty() } }
        .filter { it.isNotEmpty() }

/**
 * Removes HTML tags from Audible's `merchandising_summary` field.
 * Audible wraps descriptions in `<p>` tags; strip them for plain text.
 */
private fun stripHtmlTags(html: String): String = html.replace(Regex("<[^>]+>"), "").trim()
