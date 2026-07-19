package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.CoverSearchResults
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
 * `MetadataResources`.
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
     *
     * When [bookId] identifies a local book, the server threads that book's
     * title, primary author, and runtime into the phase-1 match scorer and
     * returns candidates ranked best-first by match confidence
     * (`0.7·duration + 0.2·title + 0.1·author`). When `null`, the underlying
     * catalog's own relevance order is preserved.
     */
    suspend fun searchBooks(
        query: String,
        region: MetadataLocale?,
        bookId: BookId? = null,
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
        region: MetadataLocale,
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
        region: MetadataLocale,
    ): AppResult<MetadataChapters?>

    /**
     * Searches for contributors matching [query] — served by the CONTRIBUTORS
     * provider chain (Audnexus today; Audible has no contributor-search API).
     *
     * If [region] is `null`, the server uses its configured default region.
     * Pass the same region here and to [getContributorMetadata]: Audnexus author
     * profiles are region-localized (a hit found in `us` can have an empty
     * profile in `fr`), so searching and previewing must hit the same catalog.
     *
     * Results are deduplicated by ASIN. Cached for 24 hours (search TTL).
     */
    suspend fun searchContributorMetadata(
        query: String,
        region: MetadataLocale? = null,
    ): AppResult<List<MetadataContributorHit>>

    /**
     * Fetches the canonical profile for the Audible contributor identified by
     * [asin] in [region].
     *
     * Returns `null` inside [AppResult.Success] when Audible returns HTTP 404
     * for the ASIN.
     */
    suspend fun getContributorMetadata(
        asin: String,
        region: MetadataLocale,
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
        region: MetadataLocale,
    ): AppResult<MetadataBook?>

    /**
     * Applies the Audible metadata for [asin] to the book at [bookId], honoring
     * the per-field [selection].
     *
     * Only fields whose flag is set in [selection] overwrite the book's current
     * value; deselected fields are left untouched. The contributor/series ASIN
     * sets choose which of the match's entries to apply (empty set = leave that
     * role/relation untouched). The server enriches the persisted book entity and
     * emits an SSE event so connected clients' Room databases receive the update.
     * The caller should confirm the match in a preview UI before calling this.
     */
    suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: MetadataLocale,
        selection: MetadataApplySelection,
    ): AppResult<Unit>

    /**
     * Applies Audible chapter *names* to the book at [bookId], by ordinal.
     *
     * Each ordinal in [ordinals] is the index of a chapter in the book's
     * start-time-ordered chapter list; that chapter takes the title of the
     * Audible chapter at the same ordinal. Local chapter start times and
     * durations are never modified — only titles. Ordinals not listed keep
     * their current title.
     *
     * The server re-fetches the Audible chapter list for [asin]/[region] and
     * re-validates that its size equals the book's chapter count. On a mismatch
     * it returns [com.calypsan.listenup.api.error.MetadataError.ChapterCountMismatch]
     * and writes nothing — guarding against a different-edition match. An empty
     * [ordinals] is a no-op success. The caller should confirm the match and let
     * the user review the names in a preview UI before calling this method.
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
     * The server enriches the persisted contributor entity (name, sort name,
     * description, image) and emits an SSE event. The caller should confirm
     * the match in a preview UI before calling this method.
     */
    suspend fun applyContributorMetadata(
        contributorId: ContributorId,
        asin: String,
        region: MetadataLocale,
    ): AppResult<Unit>

    /**
     * Searches Audible + iTunes in parallel for cover-art candidates for the book
     * at [bookId], each with probed pixel dimensions.
     *
     * If [region] is `null`, the server uses its configured default region with US
     * as a fallback for the Audible query. Each source is failure-contained: one
     * provider being down still returns the other's candidates. The caller presents
     * the options and passes a chosen [CoverOption.url] back to [applyCover].
     */
    suspend fun searchCovers(
        bookId: BookId,
        region: MetadataLocale?,
    ): AppResult<CoverSearchResults>

    /**
     * Downloads the cover image at [url], stores it as the managed cover for the
     * book at [bookId] (source `UPLOADED`), and emits an SSE event so connected
     * clients receive the change. [url] is typically a [CoverOption.url] from a
     * prior [searchCovers] call but may be any reachable image URL.
     */
    suspend fun applyCover(
        bookId: BookId,
        url: String,
    ): AppResult<Unit>
}
