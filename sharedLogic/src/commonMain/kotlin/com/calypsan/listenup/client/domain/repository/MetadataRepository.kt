@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId

/**
 * Repository over [com.calypsan.listenup.api.MetadataLookupService]. Methods
 * mirror the RPC contract method-for-method — `:contract` DTOs are exposed
 * directly to ViewModels (no parallel domain-DTO hierarchy).
 *
 * Implementation dispatches through the bounded, self-healing
 * [com.calypsan.listenup.client.data.remote.RpcChannel] for
 * [com.calypsan.listenup.api.MetadataLookupService], which folds each call's outcome into an
 * [com.calypsan.listenup.api.result.AppResult] and re-raises
 * [kotlinx.coroutines.CancellationException].
 */
interface MetadataRepository {
    /** Searches the Audible catalog for books matching [query]. */
    suspend fun searchBooks(
        query: String,
        region: MetadataLocale?,
    ): AppResult<MetadataSearchResults>

    /** Fetches the canonical metadata for the Audible book identified by [asin] in [region]. */
    suspend fun getBookMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataBook?>

    /** Fetches the chapter list for the Audible book identified by [asin] in [region]. */
    suspend fun getBookChapters(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataChapters?>

    /** Searches Audible for contributors matching [query]. */
    suspend fun searchContributorMetadata(query: String): AppResult<List<MetadataContributorHit>>

    /** Fetches the canonical profile for the Audible contributor identified by [asin] in [region]. */
    suspend fun getContributorMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataContributorProfile?>

    /** Bypasses the cache and forces a fresh fetch of the Audible metadata for [asin] in [region]. */
    suspend fun refreshBookMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataBook?>

    /**
     * Applies the Audible metadata for [asin] to the book at [bookId], honoring
     * the per-field [selection].
     *
     * The server enriches the persisted book entity and emits an SSE event so
     * connected clients' Room databases receive the update.
     */
    suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: MetadataLocale,
        selection: MetadataApplySelection,
    ): AppResult<Unit>

    /**
     * Applies Audible chapter names (by ordinal, start-time order) to the book
     * at [bookId]. Only the chapters whose ordinal is in [ordinals] are renamed;
     * local chapter timings are never changed. The server re-validates that the
     * Audible chapter count matches the book's and emits an SSE event on success.
     */
    suspend fun applyChapterNames(
        bookId: BookId,
        asin: String,
        region: MetadataLocale,
        ordinals: Set<Int>,
    ): AppResult<Unit>

    /**
     * Applies the canonical Audible contributor metadata for [asin] to the
     * contributor at [contributorId].
     *
     * The server enriches the persisted contributor entity and emits an SSE event.
     */
    suspend fun applyContributorMetadata(
        contributorId: ContributorId,
        asin: String,
        region: MetadataLocale,
    ): AppResult<Unit>
}
