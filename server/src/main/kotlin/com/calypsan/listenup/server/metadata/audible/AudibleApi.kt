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
    suspend fun search(region: AudibleRegion, params: SearchParams): AppResult<List<AudibleSearchResult>>

    /**
     * Fetches full metadata for a single audiobook by [asin] in [region].
     *
     * @return [AppResult.Success] with the book (or `null` for a 404),
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getBook(region: AudibleRegion, asin: String): AppResult<AudibleBook?>

    /**
     * Fetches the chapter list for a single audiobook by [asin] in [region].
     *
     * @return [AppResult.Success] with the chapter list (possibly empty) on success,
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getChapters(region: AudibleRegion, asin: String): AppResult<List<AudibleChapter>>
}
