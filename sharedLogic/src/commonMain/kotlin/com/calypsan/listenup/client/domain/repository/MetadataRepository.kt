package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId

/**
 * Repository over [com.calypsan.listenup.api.MetadataLookupService]. Methods
 * mirror the RPC contract method-for-method — `:contract` DTOs are exposed
 * directly to ViewModels (no parallel domain-DTO hierarchy).
 *
 * Implementation delegates to [com.calypsan.listenup.client.data.remote.MetadataLookupRpcFactory]
 * and wraps each call in a [kotlinx.coroutines.CancellationException]-preserving
 * transport-error catch.
 */
interface MetadataRepository {
    /** Searches the Audible catalog for books matching [query]. */
    suspend fun searchBooks(
        query: String,
        region: AudibleRegion?,
    ): AppResult<MetadataSearchResults>

    /** Fetches the canonical metadata for the Audible book identified by [asin] in [region]. */
    suspend fun getBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?>

    /** Fetches the chapter list for the Audible book identified by [asin] in [region]. */
    suspend fun getBookChapters(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataChapters?>

    /** Searches Audible for contributors matching [query]. */
    suspend fun searchContributorMetadata(query: String): AppResult<List<MetadataContributorHit>>

    /** Fetches the canonical profile for the Audible contributor identified by [asin] in [region]. */
    suspend fun getContributorMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataContributorProfile?>

    /** Bypasses the cache and forces a fresh fetch of the Audible metadata for [asin] in [region]. */
    suspend fun refreshBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?>

    /**
     * Applies the canonical Audible metadata for [asin] to the book at [bookId].
     *
     * The server enriches the persisted book entity and emits an SSE event so
     * connected clients' Room databases receive the update.
     */
    suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
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
        region: AudibleRegion,
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
        region: AudibleRegion,
    ): AppResult<Unit>
}
