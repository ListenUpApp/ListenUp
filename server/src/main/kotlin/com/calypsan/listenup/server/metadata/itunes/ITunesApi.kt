package com.calypsan.listenup.server.metadata.itunes

import com.calypsan.listenup.api.result.AppResult

/**
 * Substitutability interface for [ITunesClient]. Declares the minimal surface
 * [com.calypsan.listenup.server.services.MetadataService] depends on so tests
 * can inject hand-rolled fakes without constructing a real Ktor
 * [io.ktor.client.HttpClient].
 *
 * All methods mirror [ITunesClient]'s public API exactly — see that class for
 * full endpoint and error-mapping documentation.
 */
interface ITunesApi {
    /**
     * Searches iTunes for the best cover matching [title] + [author].
     *
     * @return [AppResult.Success] with a non-null [ITunesCoverHit] when a
     *   matching audiobook is found, `null` when iTunes returns no results,
     *   or a typed [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?>
}
