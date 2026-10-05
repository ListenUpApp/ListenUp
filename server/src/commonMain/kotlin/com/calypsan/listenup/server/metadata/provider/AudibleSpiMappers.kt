package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.AudibleSeriesEntry
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterMeta
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.editionFormatOf
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.SeriesMeta

// ─── Audible catalog → neutral SPI meta mappers ───────────────────────────────
//
// The re-skin of the Audible catalog onto the capability SPI: every Audible
// response type maps to a provider-neutral `*Meta` shape here so `AudibleProvider`
// bodies stay one-line `.map { it.toX() }` delegations. Pure functions — no I/O —
// so they are exhaustively unit-testable without a `MetadataService`. The neutral
// `*Meta` values feed the `EnrichmentCoordinator`, which composes them across
// providers and projects the result to the wire DTOs.

/** Minutes → milliseconds. */
private const val MS_PER_MINUTE: Long = 60_000L

/**
 * Provisional match confidence stamped on every Audible candidate until the shared
 * duration-weighted scorer lands. All hits carry the same value, so candidates keep
 * Audible's own relevance order rather than being re-ranked prematurely.
 */
private const val PROVISIONAL_MATCH_SCORE: Double = 1.0

/**
 * Maps a lightweight Audible search hit to a ranked [BookMatch] candidate. [BookMatch.score]
 * is [PROVISIONAL_MATCH_SCORE] pending the shared scorer; runtime is normalized to
 * milliseconds so the future scorer can compare it against the local file directly.
 */
internal fun AudibleSearchResult.toBookMatch(): BookMatch =
    BookMatch(
        asin = asin,
        title = title,
        author = authors.firstOrNull()?.name,
        durationMs = runtimeMinutes.takeIf { it > 0 }?.let { it * MS_PER_MINUTE },
        coverUrl = coverUrl.takeIf { it.isNotBlank() },
        score = PROVISIONAL_MATCH_SCORE,
    )

/**
 * Maps a full Audible book to the neutral [BookCoreMeta], folding book credits into
 * [BookCoreMeta.authors] / [BookCoreMeta.narrators]. Audible exposes neither an explicit
 * nor an abridged flag on this shape, so both stay `null`.
 */
internal fun AudibleBook.toBookCoreMeta(): BookCoreMeta =
    BookCoreMeta(
        title = title.takeIf { it.isNotBlank() },
        subtitle = subtitle.takeIf { it.isNotBlank() },
        description = description.takeIf { it.isNotBlank() },
        publisher = publisher.takeIf { it.isNotBlank() },
        releaseDate = releaseDate.takeIf { it.isNotBlank() },
        language = language.takeIf { it.isNotBlank() },
        runtimeMinutes = runtimeMinutes.takeIf { it > 0 },
        explicit = null,
        abridged = null,
        authors = authors.map { it.toBookContributorMeta(ContributorRole.AUTHOR) },
        narrators = narrators.map { it.toBookContributorMeta(ContributorRole.NARRATOR) },
    )

/** Maps an Audible contributor credit to a [BookContributorMeta] with the given [role]. */
internal fun AudibleContributor.toBookContributorMeta(role: ContributorRole): BookContributorMeta =
    BookContributorMeta(
        key = asin.takeIf { it.isNotBlank() },
        name = name,
        role = role,
    )

/**
 * Maps Audible's chapter list to a [ChapterListMeta]. Audible chapters are catalog-verified
 * markers, so [ChapterListMeta.accurate] is `true`. Returns `null` for an empty list so the
 * SPI's "catalog miss" convention holds.
 */
internal fun List<AudibleChapter>.toChapterListMeta(): ChapterListMeta? =
    takeIf { it.isNotEmpty() }?.let { chapters ->
        ChapterListMeta(
            chapters = chapters.map { it.toChapterMeta() },
            accurate = true,
        )
    }

/** Maps a single Audible chapter marker to a [ChapterMeta]. */
internal fun AudibleChapter.toChapterMeta(): ChapterMeta =
    ChapterMeta(
        title = title.takeIf { it.isNotBlank() },
        startMs = startMs,
        lengthMs = durationMs.takeIf { it > 0 },
    )

/** Maps an Audible series placement to a neutral [SeriesMeta]. */
internal fun AudibleSeriesEntry.toSeriesMeta(): SeriesMeta =
    SeriesMeta(
        key = asin.takeIf { it.isNotBlank() },
        title = name,
        sequence = position.takeIf { it.isNotBlank() },
    )

/**
 * Maps Audible's flat genre labels to [GenreMeta]s. Audible genres are curated shelf
 * categories, so each is [GenreKind.GENRE]; free-form topic tags (moods/tropes) are a
 * separate scrape and are not folded in here.
 */
internal fun List<String>.toGenreMetas(): List<GenreMeta> =
    mapNotNull { name -> name.takeIf { it.isNotBlank() }?.let { GenreMeta(name = it, kind = GenreKind.GENRE) } }

/**
 * Maps a full Audible book to its neutral [ExternalRatingMeta], or `null` when Audible has no
 * rating for it. Audible reports a 0 average *and* a 0 count for a book with no ratings yet
 * rather than omitting the fields, so either being zero is treated as a catalog miss — a wrong
 * score is worse than none.
 *
 * [rating] round-trips through [Float.toString] before parsing back to [Double]: a raw
 * `Float.toDouble()` widening carries the float's binary imprecision along with it (`4.8f` widens
 * to `4.800000190734863`), while Audible's `display_average_rating` is only ever one decimal digit
 * of real precision.
 *
 * [region] is the Audible storefront that actually answered — see
 * [com.calypsan.listenup.server.services.MetadataService.getBookInAnyRegion] — and is stamped
 * onto [ExternalRatingMeta.region] so a region-locked book's rating is attributed to the store
 * that sells it, not the one the caller asked for.
 */
internal fun AudibleBook.toExternalRatingMeta(region: AudibleRegion = AudibleRegion.US): ExternalRatingMeta? =
    takeIf { rating > 0f && ratingCount > 0 }
        ?.let { ExternalRatingMeta(average = rating.toString().toDouble(), count = ratingCount, region = region.code) }

/**
 * Selects the single canonical cover from an Audible search: the first result carrying a
 * non-blank cover URL (Audible search returns one canonical cover per edition). Empty when
 * no result has a cover.
 */
internal fun List<AudibleSearchResult>.toCoverMetas(): List<CoverMeta> =
    firstOrNull { it.coverUrl.isNotBlank() }
        ?.let { listOf(CoverMeta(url = it.coverUrl, sourceKey = it.asin)) }
        ?: emptyList()

/** An Audible search hit as Find reads it, found in [region]'s store. */
internal fun AudibleSearchResult.toFoundBook(region: AudibleRegion): FoundBook =
    FoundBook(
        key = asin,
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        authors = authors.map { it.name },
        narrators = narrators.map { it.name },
        durationMs = runtimeMinutes.takeIf { it > 0 }?.let { it * MS_PER_MINUTE },
        releaseDate = releaseDate.takeIf { it.isNotBlank() },
        format = editionFormatOf(formatType.takeIf { it.isNotBlank() }, title, subtitle),
        asin = asin,
        coverUrl = coverUrl.takeIf { it.isNotBlank() },
        region = region.code,
    )

/** A book Audible returned for one of its ASINs, as Find reads it — [viaLink] when the ASIN was the book's link. */
internal fun AudibleBook.toFoundBook(
    region: AudibleRegion,
    viaLink: Boolean,
    chapterCount: Int?,
): FoundBook =
    FoundBook(
        key = asin,
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        authors = authors.map { it.name },
        narrators = narrators.map { it.name },
        durationMs = runtimeMinutes.takeIf { it > 0 }?.let { it * MS_PER_MINUTE },
        releaseDate = releaseDate.takeIf { it.isNotBlank() },
        format = editionFormatOf(formatType.takeIf { it.isNotBlank() }, title, subtitle),
        asin = asin,
        coverUrl = coverUrl.takeIf { it.isNotBlank() },
        region = region.code,
        chapterCount = chapterCount,
        viaLink = viaLink,
    )
