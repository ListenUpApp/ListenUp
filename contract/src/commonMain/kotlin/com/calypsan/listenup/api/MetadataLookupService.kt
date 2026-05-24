package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for external metadata lookup and application.
 *
 * Wraps the server's Audible and iTunes adapters with TTL caching and
 * region-aware fallback. Clients use this to search for a canonical metadata
 * match, preview it, and apply it to a local book or contributor entity.
 *
 * Wire shapes are defined in [com.calypsan.listenup.api.dto.MetadataBook] and
 * siblings. The third-party REST mirrors are in
 * [com.calypsan.listenup.api.resources.MetadataResources].
 *
 * Two complementary surfaces:
 * - **Search / fetch** — [searchBooks], [getBookMetadata], [getBookChapters],
 *   [searchContributorMetadata], [getContributorMetadata] read external data
 *   (with caching) and return wire DTOs. These are safe to call repeatedly.
 * - **Apply** — [applyBookMetadata], [applyContributorMetadata] write through
 *   the syncable substrate: the server enriches the persisted entity and emits
 *   an SSE event so all connected clients see the change. These are mutating
 *   operations and should be called once per user intent.
 */
@Rpc
interface MetadataLookupService {
    /**
     * Searches the Audible catalog for books matching [query].
     *
     * If [region] is `null`, the server uses its configured default region with
     * US as a fallback when the default returns no results. Passing an explicit
     * [region] skips the fallback and queries that region directly.
     *
     * Results are cached for 24 hours (search-TTL). Repeated calls with the
     * same arguments are cheap.
     */
    suspend fun searchBooks(
        query: String,
        region: AudibleRegion?,
    ): AppResult<MetadataSearchResults>

    /**
     * Fetches the canonical metadata for the Audible book identified by [asin]
     * in [region].
     *
     * Returns `null` inside [AppResult.Success] when Audible returns HTTP 404
     * for the ASIN; null is cached for the same 7-day TTL so repeated lookups
     * for unknown ASINs don't hammer the external API.
     */
    suspend fun getBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?>

    /**
     * Fetches the chapter list for the Audible book identified by [asin] in
     * [region].
     *
     * Returns `null` inside [AppResult.Success] when chapter data is
     * unavailable. Cached for 30 days — chapter lists rarely change after a
     * book is published.
     */
    suspend fun getBookChapters(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataChapters?>

    /**
     * Searches Audible for contributors matching [query].
     *
     * **Currently stubs to an empty list.** Audible has no official
     * contributor-search API; the Go reference implementation used HTML
     * scraping of `www.audible.com/search?searchAuthor=`, which requires a
     * Kotlin-first HTML parser dependency not yet added to `:server`. A later
     * phase will land a proper implementation — either via HTML scraping or an
     * alternative metadata source (e.g. MusicBrainz, OpenLibrary).
     *
     * The method signature is included in the contract now so client code can
     * be written against it; the stub will be transparently replaced by the
     * real implementation without a contract change.
     */
    suspend fun searchContributorMetadata(query: String): AppResult<List<MetadataContributorHit>>

    /**
     * Fetches the canonical profile for the Audible contributor identified by
     * [asin] in [region].
     *
     * Returns `null` inside [AppResult.Success] when Audible returns HTTP 404
     * for the ASIN.
     */
    suspend fun getContributorMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataContributorProfile?>

    /**
     * Bypasses the cache and forces a fresh fetch of the Audible metadata for
     * the book at [asin] in [region].
     *
     * Use sparingly — this counts against the per-region rate limit. Intended
     * for operator or power-user workflows where the cached data is stale.
     */
    suspend fun refreshBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?>

    /**
     * Applies the canonical Audible metadata for [asin] to the book at
     * [bookId].
     *
     * The server enriches the persisted book entity (title, subtitle,
     * description, contributors, series, cover) and emits an SSE event so
     * connected clients' Room databases receive the update. The caller should
     * confirm the match in a preview UI before calling this method.
     */
    suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit>

    /**
     * Applies the canonical Audible contributor metadata for [asin] to the
     * contributor at [contributorId].
     *
     * The server enriches the persisted contributor entity (name, sort name,
     * description, image) and emits an SSE event. The caller should confirm
     * the match in a preview UI before calling this method.
     */
    suspend fun applyContributorMetadata(
        contributorId: ContributorId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit>
}
