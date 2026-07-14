package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.result.AppResult

/**
 * Substitutability interface for [AudibleClient]. Declares the minimal surface
 * [MetadataService] depends on so tests can inject hand-rolled fakes without
 * constructing a real Ktor [io.ktor.client.HttpClient].
 *
 * All methods mirror [AudibleClient]'s public API exactly — see that class for
 * full endpoint and error-mapping documentation.
 */
interface AudibleApi {
    /**
     * Searches the Audible catalog using [params] in [region].
     *
     * @return [AppResult.Success] with the result list (possibly empty) on success,
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>>

    /**
     * Fetches full metadata for a single audiobook by [asin] in [region].
     *
     * @return [AppResult.Success] with the book (or `null` for a 404),
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?>

    /**
     * Fetches the chapter list for a single audiobook by [asin] in [region].
     *
     * @return [AppResult.Success] with the chapter list (possibly empty) on success,
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>>

    /**
     * Fetches the contributor profile for [asin] in [region] by scraping the
     * Audible author page at `www.audible.{tld}/author/x/{asin}`.
     *
     * The official catalog API no longer returns contributor images or
     * biographies, so web scraping is the only reliable source.
     *
     * @return [AppResult.Success] with the profile (or `null` for a 404),
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getContributor(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleContributorProfile?>

    /**
     * Searches Audible's contributor catalog by [name] using HTML scraping of
     * `www.audible.{tld}/search?searchAuthor={name}`.
     *
     * The official catalog API offers no contributor-search endpoint, so this
     * relies on HTML scraping.
     *
     * Results are deduplicated by ASIN and ranked by name-similarity to [name].
     *
     * @return [AppResult.Success] with a (possibly empty) result list on success,
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ): AppResult<List<AudibleContributorProfile>>

    /**
     * Scrapes the typed topic tags (moods, themes, …) from the Audible product
     * page at `www.audible.{tld}/pd/{asin}` in [region].
     *
     * The catalog API does not expose these recommendation tags, so the product
     * page is scraped — gated behind the storefront locale cookie (Task 4) so
     * `/pd` returns 200 rather than 503. Classification into Moods / Tropes is
     * [ProductTagClassifier]'s job; this method returns the raw typed tags.
     *
     * Best-effort: a missing page or transport failure yields an empty list or a
     * typed [com.calypsan.listenup.api.error.MetadataError]; it never throws past
     * the boundary.
     *
     * @return [AppResult.Success] with a (possibly empty) tag list on success,
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>>
}
