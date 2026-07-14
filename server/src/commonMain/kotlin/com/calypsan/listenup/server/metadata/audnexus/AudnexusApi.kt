package com.calypsan.listenup.server.metadata.audnexus

import com.calypsan.listenup.api.result.AppResult

/**
 * Substitutability interface for [AudnexusClient]. Declares the minimal surface
 * [com.calypsan.listenup.server.metadata.provider.AudnexusProvider] depends on so
 * tests can inject hand-rolled fakes without constructing a real Ktor
 * [io.ktor.client.HttpClient].
 *
 * Every method mirrors [AudnexusClient]'s public API exactly — see that class for
 * full endpoint and error-mapping documentation. [region] is the provider-neutral
 * lowercase market token (`us`, `uk`, `de`, …), the same vocabulary as
 * [com.calypsan.listenup.api.metadata.MetadataLocale.region].
 */
internal interface AudnexusApi {
    /**
     * Fetches full metadata for a book by [asin] in [region].
     *
     * @return [AppResult.Success] with the book, or `null` when the catalog has no
     *   entry (404); a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getBook(
        asin: String,
        region: String,
    ): AppResult<AudnexusBook?>

    /**
     * Fetches the chapter list for a book by [asin] in [region].
     *
     * @return [AppResult.Success] with the chapters, or `null` when the catalog has
     *   none (404); a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getChapters(
        asin: String,
        region: String,
    ): AppResult<AudnexusChapters?>

    /**
     * Searches author profiles by [name] in [region].
     *
     * @return [AppResult.Success] with a (possibly empty) hit list; a typed
     *   [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun searchAuthors(
        name: String,
        region: String,
    ): AppResult<List<AudnexusAuthor>>

    /**
     * Fetches the author profile for [asin] in [region].
     *
     * @return [AppResult.Success] with the profile, or `null` when the catalog has no
     *   such author (404); a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun getAuthor(
        asin: String,
        region: String,
    ): AppResult<AudnexusAuthorProfile?>
}
