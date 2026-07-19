package com.calypsan.listenup.server.routes.resources

import io.ktor.resources.Resource

/**
 * REST mirror of the [com.calypsan.listenup.api.MetadataLookupService] RPC
 * surface. All routes live under `/api/v1/metadata/`.
 *
 * Region values in query and path parameters are short market-code strings
 * (e.g. `"us"`, `"uk"`), matching the operator-facing convention used throughout
 * the API. The server route handler parses each into a provider-neutral
 * [com.calypsan.listenup.api.metadata.MetadataLocale] before calling the service.
 *
 * [Search] is the parent resource and doubles as the top-level collection route.
 */
@Resource("/api/v1/metadata/search")
class MetadataResources(
    /** Full-text search query. */
    val query: String = "",
    /**
     * Optional region code (e.g. `"us"`, `"uk"`). When omitted the server uses
     * its configured default region with US as a fallback.
     *
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.searchBooks].
     */
    val region: String? = null,
    /**
     * Optional local book id. When present, the server threads that book's
     * title/author/runtime into the match scorer and returns candidates ranked
     * best-first by match confidence.
     */
    val bookId: String? = null,
) {
    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.getBookMetadata] —
     * `GET /api/v1/metadata/book/{asin}?region=us` fetches the Audible book
     * metadata for [asin] in the given [region]. Responds 200 with
     * [com.calypsan.listenup.api.dto.MetadataBook] on success, 204 when the
     * ASIN is unknown, or a typed
     * [com.calypsan.listenup.api.error.MetadataError] on failure.
     */
    @Resource("/api/v1/metadata/book/{asin}")
    class Book(
        val asin: String,
        /** Region code (e.g. `"us"`). Required. */
        val region: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.getBookChapters] —
     * `GET /api/v1/metadata/book/{asin}/chapters?region=us` fetches the chapter
     * list for [asin] in [region]. Responds 200 with
     * [com.calypsan.listenup.api.dto.MetadataChapters], 204 when chapter data
     * is unavailable, or a typed error on failure.
     */
    @Resource("/api/v1/metadata/book/{asin}/chapters")
    class Chapters(
        val asin: String,
        /** Region code (e.g. `"us"`). Required. */
        val region: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.refreshBookMetadata] —
     * `POST /api/v1/metadata/book/{asin}/refresh?region=us` bypasses the cache
     * and forces a fresh fetch for [asin] in [region]. Responds 200 with the
     * refreshed [com.calypsan.listenup.api.dto.MetadataBook] on success.
     *
     * Use sparingly — counts against the per-region rate limit.
     */
    @Resource("/api/v1/metadata/book/{asin}/refresh")
    class BookRefresh(
        val asin: String,
        /** Region code (e.g. `"us"`). Required. */
        val region: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.searchContributorMetadata] —
     * `GET /api/v1/metadata/contributors/search?query=…&region=us` searches for
     * contributors matching [query], deduplicated by ASIN. When [region] is
     * omitted the server uses its default region. Pass the same region to the profile fetch — profiles are
     * region-localized.
     */
    @Resource("/api/v1/metadata/contributors/search")
    class ContributorSearch(
        /** Contributor name query, e.g. "Brandon Sanderson". */
        val query: String = "",
        /** Optional region code (e.g. `"us"`, `"de"`). */
        val region: String? = null,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.getContributorMetadata] —
     * `GET /api/v1/metadata/contributor/{asin}?region=us` fetches the Audible
     * contributor profile for [asin] in [region]. Responds 200 with
     * [com.calypsan.listenup.api.dto.MetadataContributorProfile], 204 when
     * unknown, or a typed error on failure.
     */
    @Resource("/api/v1/metadata/contributor/{asin}")
    class Contributor(
        val asin: String,
        /** Region code (e.g. `"us"`). Required. */
        val region: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.applyBookMetadata] —
     * `POST /api/v1/metadata/apply/book/{bookId}?asin=…&region=us` with a
     * [com.calypsan.listenup.api.dto.MetadataApplySelection] JSON body selecting which
     * fields to apply. Responds 200 on success or a typed error on failure.
     */
    @Resource("/api/v1/metadata/apply/book/{bookId}")
    class ApplyBook(
        /** Our internal book identifier. */
        val bookId: String,
        /** Audible ASIN of the metadata to apply. */
        val asin: String,
        /** Region code (e.g. `"us"`). Required. */
        val region: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.applyContributorMetadata] —
     * `POST /api/v1/metadata/apply/contributor/{contributorId}?asin=…&region=us`
     * applies the Audible contributor metadata for [asin] to the contributor at
     * [contributorId]. Responds 200 on success or a typed error on failure.
     */
    @Resource("/api/v1/metadata/apply/contributor/{contributorId}")
    class ApplyContributor(
        /** Our internal contributor identifier. */
        val contributorId: String,
        /** Audible ASIN of the metadata to apply. */
        val asin: String,
        /** Region code (e.g. `"us"`). Required. */
        val region: String,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.applyChapterNames] —
     * `POST /api/v1/metadata/apply/chapters/{bookId}?asin=…&region=us&ordinals=0&ordinals=2`
     * applies Audible chapter names (by ordinal, start-time order) to the book at
     * [bookId]. Responds 200 on success, or a typed error — including
     * [com.calypsan.listenup.api.error.MetadataError.ChapterCountMismatch] when the
     * edition's chapter count differs — on failure.
     */
    @Resource("/api/v1/metadata/apply/chapters/{bookId}")
    class ApplyChapters(
        /** Our internal book identifier. */
        val bookId: String,
        /** Audible ASIN of the metadata to apply. */
        val asin: String,
        /** Region code (e.g. `"us"`). Required. */
        val region: String,
        /** Chapter ordinals (start-time order) whose names to apply; empty = no-op. */
        val ordinals: List<Int> = emptyList(),
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.searchCovers] —
     * `GET /api/v1/metadata/covers/{bookId}?region=us` searches Audible + iTunes for
     * cover-art candidates for the book at [bookId]. Responds 200 with
     * [com.calypsan.listenup.api.dto.CoverSearchResults] on success or a typed error
     * on failure. When [region] is omitted the server uses its default region with
     * US fallback.
     */
    @Resource("/api/v1/metadata/covers/{bookId}")
    class SearchCovers(
        /** Our internal book identifier. */
        val bookId: String,
        /** Optional region code (e.g. `"us"`). */
        val region: String? = null,
    )

    /**
     * REST mirror of [com.calypsan.listenup.api.MetadataLookupService.applyCover] —
     * `POST /api/v1/metadata/apply/cover/{bookId}?url=…` downloads the image at [url]
     * and stores it as the managed cover (source `UPLOADED`) for the book at [bookId].
     * Responds 200 on success or a typed error on failure.
     */
    @Resource("/api/v1/metadata/apply/cover/{bookId}")
    class ApplyCover(
        /** Our internal book identifier. */
        val bookId: String,
        /** The image URL to download and store as the cover. */
        val url: String,
    )
}
