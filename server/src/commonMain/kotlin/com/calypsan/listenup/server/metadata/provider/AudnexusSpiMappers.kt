package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthor
import com.calypsan.listenup.server.metadata.audnexus.AudnexusAuthorProfile
import com.calypsan.listenup.server.metadata.audnexus.AudnexusBook
import com.calypsan.listenup.server.metadata.audnexus.AudnexusChapter
import com.calypsan.listenup.server.metadata.audnexus.AudnexusChapters
import com.calypsan.listenup.server.metadata.audnexus.AudnexusGenre
import com.calypsan.listenup.server.metadata.audnexus.AudnexusNarrator
import com.calypsan.listenup.server.metadata.audnexus.AudnexusSeries
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterMeta
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.GenreKind
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.SeriesMeta

// ─── Audnexus catalog → neutral SPI meta mappers ──────────────────────────────
//
// The re-skin of the Audnexus catalog onto the capability SPI: every Audnexus
// response type maps to a provider-neutral `*Meta` shape here so `AudnexusProvider`
// bodies stay thin `.map { it.toX() }` delegations. Pure functions — no I/O — so
// they are exhaustively unit-testable without an HTTP client. The neutral `*Meta`
// values feed the `EnrichmentCoordinator`, which composes them across providers.

/** The Audnexus genre `type` token that marks a free-form tag rather than a formal genre. */
private const val TAG_TYPE = "tag"

/**
 * Maps a full Audnexus book to the neutral [BookCoreMeta], folding credits into
 * [BookCoreMeta.authors] / [BookCoreMeta.narrators]. Audnexus reports no per-book
 * runtime here (its chapters endpoint owns duration), no explicit flag, and no
 * abridged flag, so those stay `null`.
 */
internal fun AudnexusBook.toBookCoreMeta(): BookCoreMeta =
    BookCoreMeta(
        title = title.takeIf { it.isNotBlank() },
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        publisher = publisherName?.takeIf { it.isNotBlank() },
        releaseDate = releaseDate?.takeIf { it.isNotBlank() },
        language = language?.takeIf { it.isNotBlank() },
        runtimeMinutes = null,
        explicit = null,
        abridged = null,
        authors = authors.map { it.toBookContributorMeta() },
        narrators = narrators.map { it.toBookContributorMeta() },
    )

/** Maps an Audnexus author credit to a [BookContributorMeta] (its ASIN is the profile key). */
internal fun AudnexusAuthor.toBookContributorMeta(): BookContributorMeta =
    BookContributorMeta(
        key = asin?.takeIf { it.isNotBlank() },
        name = name,
        role = ContributorRole.AUTHOR,
    )

/** Maps an Audnexus narrator credit to a [BookContributorMeta]; Audnexus exposes no narrator key. */
internal fun AudnexusNarrator.toBookContributorMeta(): BookContributorMeta =
    BookContributorMeta(
        key = null,
        name = name,
        role = ContributorRole.NARRATOR,
    )

/**
 * Maps Audnexus chapters to a [ChapterListMeta], carrying the catalog's [AudnexusChapters.isAccurate]
 * flag and its brand intro/outro padding (0 collapses to `null`). Returns `null` for an empty list so
 * the SPI's "catalog miss" convention holds.
 */
internal fun AudnexusChapters.toChapterListMeta(): ChapterListMeta? =
    chapters.takeIf { it.isNotEmpty() }?.let { list ->
        ChapterListMeta(
            chapters = list.map { it.toChapterMeta() },
            accurate = isAccurate,
            brandIntroMs = brandIntroDurationMs.takeIf { it > 0 },
            brandOutroMs = brandOutroDurationMs.takeIf { it > 0 },
        )
    }

/** Maps a single Audnexus chapter marker to a [ChapterMeta]. */
internal fun AudnexusChapter.toChapterMeta(): ChapterMeta =
    ChapterMeta(
        title = title.takeIf { it.isNotBlank() },
        startMs = startOffsetMs,
        lengthMs = lengthMs.takeIf { it > 0 },
    )

/** Selects the single Audnexus cover: its [AudnexusBook.image], keyed by ASIN. Empty when absent. */
internal fun AudnexusBook.toCoverMetas(): List<CoverMeta> =
    image?.takeIf { it.isNotBlank() }?.let { listOf(CoverMeta(url = it, sourceKey = asin)) } ?: emptyList()

/**
 * Maps an Audnexus book's primary + secondary series placements to neutral [SeriesMeta]s, in that
 * order, dropping placements with a blank name.
 */
internal fun AudnexusBook.toSeriesMetas(): List<SeriesMeta> =
    listOfNotNull(seriesPrimary, seriesSecondary).mapNotNull { it.toSeriesMetaOrNull() }

/** Maps an Audnexus series placement to a [SeriesMeta], or `null` when its name is blank. */
internal fun AudnexusSeries.toSeriesMetaOrNull(): SeriesMeta? =
    name.takeIf { it.isNotBlank() }?.let {
        SeriesMeta(
            key =
                asin?.takeIf { a ->
                    a.isNotBlank()
                },
            title = it,
            sequence = position?.takeIf { p -> p.isNotBlank() },
        )
    }

/**
 * Maps Audnexus genre-family terms to [GenreMeta]s, routing each by its [AudnexusGenre.type]:
 * `"tag"` → [GenreKind.TAG], anything else → [GenreKind.GENRE]. Blank-named terms are dropped.
 */
internal fun List<AudnexusGenre>.toGenreMetas(): List<GenreMeta> =
    mapNotNull { genre ->
        genre.name.takeIf { it.isNotBlank() }?.let { name ->
            GenreMeta(
                name = name,
                kind = if (genre.type.equals(TAG_TYPE, ignoreCase = true)) GenreKind.TAG else GenreKind.GENRE,
            )
        }
    }

/**
 * Maps an Audnexus author search hit to a [ContributorHitMeta], or `null` when it carries no ASIN
 * (the fetch key) — a keyless hit can't be followed to a profile, so it is dropped.
 */
internal fun AudnexusAuthor.toContributorHitMetaOrNull(): ContributorHitMeta? =
    asin?.takeIf { it.isNotBlank() }?.let { ContributorHitMeta(key = it, name = name) }

/** Maps an Audnexus author profile to the neutral [ContributorMeta] (name + bio + photo). */
internal fun AudnexusAuthorProfile.toContributorMeta(): ContributorMeta =
    ContributorMeta(
        key = asin,
        name = name,
        description = description?.takeIf { it.isNotBlank() },
        imageUrl = image?.takeIf { it.isNotBlank() },
    )
