package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult

/**
 * The root of the metadata provider SPI: one narrow capability a provider *may*
 * implement.
 *
 * A concrete provider is a single object that implements as many of the capability
 * sub-interfaces as its catalog supports — Audible is a [BookIdentitySource] +
 * [BookCoreSource] + [CoverSource]; Audnexus adds [ContributorSource] and
 * [GenreSource]. Splitting each concern into its own interface means no provider is
 * ever forced to stub a capability it can't honor (the reason contributor *profiles*
 * are their own [ContributorSource] rather than a method on every provider).
 *
 * The router discovers capabilities through
 * [MetadataProviderRegistry.capable] — `filterIsInstance` over the registered
 * providers — so adding a capability to a provider is just implementing one more
 * interface.
 *
 * ### Return convention (uniform across every capability)
 * - [AppResult.Failure] means the provider *errored* — network fault, parse
 *   failure, rate-limit exhaustion. The router treats it as a real failure and its
 *   containment kicks in.
 * - `Success(null)` / `Success(emptyList())` means "this catalog simply has nothing
 *   for that book" — a normal, expected miss. The router's first-non-empty
 *   composition falls through to the next provider *without* tripping failure
 *   containment.
 *
 * Keeping "empty" distinct from "failed" is what lets a lean catalog sit early in a
 * precedence chain without poisoning it.
 */
sealed interface MetadataCapability {
    /** The provider this capability belongs to. */
    val id: MetadataProviderId
}

/**
 * Phase-1 identity: search a catalog for candidates matching a free-text query and
 * rank them. This is the "which catalog entry is this local book?" step, distinct
 * from fetching the winning entry's fields.
 */
interface BookIdentitySource : MetadataCapability {
    /**
     * Searches the catalog for books matching [query] in [locale]. Returns ranked
     * [BookMatch] candidates (highest [BookMatch.score] first). `Success(emptyList())`
     * for no hits; [AppResult.Failure] only on a provider error.
     */
    suspend fun searchBooks(
        query: String,
        locale: MetadataLocale,
    ): AppResult<List<BookMatch>>
}

/**
 * Fetches the core metadata (and book credits) for an identified book.
 */
interface BookCoreSource : MetadataCapability {
    /**
     * Fetches core metadata for [book] in [locale]. `Success(null)` when the catalog
     * has no entry for it; [AppResult.Failure] only on a provider error. Pass
     * [refresh] = `true` to bypass any provider-side cache.
     */
    suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): AppResult<BookCoreMeta?>
}

/**
 * Contributor *profiles* — search and fetch a person's bio/photo. Book *credits*
 * do NOT live here (they fold into [BookCoreMeta.authors] / [BookCoreMeta.narrators]);
 * this keeps the capability honest for catalogs like Audible that have credits but
 * no standalone profile endpoint.
 */
interface ContributorSource : MetadataCapability {
    /**
     * Searches contributor profiles by [name] in [locale]. `Success(emptyList())`
     * for no hits; [AppResult.Failure] only on a provider error.
     */
    suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>>

    /**
     * Fetches the profile for the contributor identified by [key] in [locale].
     * `Success(null)` when the catalog has no such profile; [AppResult.Failure] only
     * on a provider error. Pass [refresh] = `true` to bypass any provider-side cache.
     */
    suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): AppResult<ContributorMeta?>
}

/**
 * Fetches a chapter list for an identified book.
 */
interface ChapterSource : MetadataCapability {
    /**
     * Fetches chapters for [book] in [locale]. `Success(null)` when the catalog has
     * no chapter data; [AppResult.Failure] only on a provider error. Pass [refresh] =
     * `true` to bypass any provider-side cache — chapter lists carry a long TTL, so a
     * refresh is the only way to pick up a corrected catalog list.
     */
    suspend fun getChapters(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean = false,
    ): AppResult<ChapterListMeta?>
}

/**
 * Searches cover-art candidates for an identified book.
 */
interface CoverSource : MetadataCapability {
    /**
     * Searches cover candidates for [book] in [locale]. `Success(emptyList())` for
     * no hits; [AppResult.Failure] only on a provider error.
     */
    suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>>
}

/**
 * Fetches series placements for an identified book.
 */
interface SeriesSource : MetadataCapability {
    /**
     * Fetches series placements for [book] in [locale]. `Success(null)`/empty when
     * the catalog has none; [AppResult.Failure] only on a provider error.
     */
    suspend fun getSeries(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<SeriesMeta>?>
}

/**
 * Fetches genre-family terms for an identified book.
 */
interface GenreSource : MetadataCapability {
    /**
     * Fetches genres/tags for [book] in [locale]. `Success(null)`/empty when the
     * catalog has none; [AppResult.Failure] only on a provider error.
     */
    suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?>
}

/**
 * Fetches a book's genre *ladders* — the ordered root→leaf category paths a catalog
 * files the book under (e.g. `["Fiction", "Science Fiction & Fantasy", "Fantasy"]`).
 *
 * A narrow, optional companion to [GenreSource]: [GenreSource] yields the flat genre
 * terms every apply reconciles, while this yields the *hierarchy* that lets browsing a
 * parent genre surface the book. Audible is the only catalog that exposes ladders, so
 * this is its own capability rather than a method every provider must stub — the same
 * reason contributor profiles are their own [ContributorSource].
 */
interface GenreLadderSource : MetadataCapability {
    /**
     * Fetches the root→leaf genre ladders for [book] in [locale]. `Success(null)`/empty
     * when the catalog exposes none; [AppResult.Failure] only on a provider error.
     */
    suspend fun getGenreLadders(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<List<String>>?>
}

/**
 * Fetches per-book characters. The honest empty slot: no public source implements
 * this today (see [CharacterMeta]). Defined so the vocabulary is complete and a
 * future manual/community source can slot in without a schema change.
 */
interface CharacterSource : MetadataCapability {
    /**
     * Fetches characters for [book] in [locale]. `Success(null)`/empty when none are
     * known; [AppResult.Failure] only on a provider error.
     */
    suspend fun getCharacters(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CharacterMeta>?>
}
