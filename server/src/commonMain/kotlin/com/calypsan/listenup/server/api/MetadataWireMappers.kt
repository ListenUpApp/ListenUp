package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MatchProvenance
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapter
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.server.metadata.ComposedBook
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookMatch
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterMeta
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.displayLabel

// ─── Composed neutral metadata → wire DTO mappers ─────────────────────────────
//
// The single boundary where the enrichment coordinator's provider-neutral result is
// projected onto the client-facing wire DTOs. Pure functions — no I/O — so the
// projection is unit-testable without a coordinator, and the lookup service body stays
// request-shape + one `.toX()` transform.

private const val MS_PER_MINUTE: Long = 60_000L

/** Projects a fully composed book onto the wire [MetadataBook]. Moods/tags stay empty (no live source). */
internal fun ComposedBook.toMetadataBook(): MetadataBook =
    MetadataBook(
        asin = asin.orEmpty(),
        title = core.title.orEmpty(),
        subtitle = core.subtitle,
        description = core.description,
        publisher = core.publisher,
        releaseDate = core.releaseDate,
        runtimeMinutes = core.runtimeMinutes,
        language = core.language,
        authors = core.authors.map { it.toContributorRef() },
        narrators = core.narrators.map { it.toContributorRef() },
        series = series.map { it.toSeriesRef() },
        genres = genres.map { it.name },
        coverUrl = coverUrl,
        coverUrlMaxSize = coverUrlMaxSize,
    )

/**
 * Projects a [ComposedBook]'s per-field winners onto the wire [MatchProvenance].
 * - [MatchProvenance.fallbackFields]: non-cover fields whose winner ≠ the field's configured primary
 *   (`routes.orderFor(field).first()`) — "this field fell through to a fallback provider."
 * - [MatchProvenance.contributingSources]: distinct provider labels across all field winners + the
 *   applied cover's winner, in BookField order (deterministic).
 * - cover source/dimensions from the applied cover's winner and the probed [coverDimensions].
 */
internal fun buildMatchProvenance(
    composed: ComposedBook,
    routes: EnrichmentRoutes,
    coverDimensions: Pair<Int, Int>?,
): MatchProvenance {
    val fieldProviders = composed.fieldProviders
    val fallbackFields =
        fieldProviders
            .filterKeys { it != BookField.COVER }
            .filter { (field, winner) -> routes.orderFor(field).firstOrNull() != winner }
            .mapValues { (_, winner) -> winner.displayLabel() }
    // The applied cover is `coverUrlMaxSize ?: coverUrl`. When no provider set a max-size URL
    // (e.g. iTunes had no match), the applied cover is the primary-url winner — report THAT source
    // so the badge and probed dimensions aren't dropped for the common Audible-only-cover case.
    val coverWinner = composed.coverMaxSizeWinner ?: fieldProviders[BookField.COVER]
    val contributing =
        (BookField.entries.mapNotNull { fieldProviders[it] } + listOfNotNull(coverWinner))
            .map { it.displayLabel() }
            .distinct()
    return MatchProvenance(
        contributingSources = contributing,
        fallbackFields = fallbackFields,
        coverSource = coverWinner?.displayLabel(),
        coverWidth = coverDimensions?.first,
        coverHeight = coverDimensions?.second,
    )
}

/**
 * Projects a phase-1 search candidate onto the wire [MetadataBook]. Deliberately lean — a
 * [BookMatch] carries only identity fields; the rich detail loads when the user picks a
 * match and the ASIN-keyed [ComposedBook.toMetadataBook] preview runs.
 */
internal fun BookMatch.toMetadataBook(): MetadataBook =
    MetadataBook(
        asin = asin.orEmpty(),
        title = title,
        subtitle = null,
        description = null,
        publisher = null,
        releaseDate = null,
        runtimeMinutes = durationMs?.let { (it / MS_PER_MINUTE).toInt() }?.takeIf { it > 0 },
        language = null,
        authors = author?.let { listOf(MetadataContributorRef(asin = null, name = it)) }.orEmpty(),
        narrators = emptyList(),
        series = emptyList(),
        genres = emptyList(),
        coverUrl = coverUrl,
        coverUrlMaxSize = null,
    )

/** Projects a neutral chapter list onto the wire [MetadataChapters]; `null` for an empty list. */
internal fun ChapterListMeta.toMetadataChapters(): MetadataChapters? =
    chapters.takeIf { it.isNotEmpty() }?.let { list ->
        MetadataChapters(chapters = list.map { it.toMetadataChapter() })
    }

private fun ChapterMeta.toMetadataChapter(): MetadataChapter =
    MetadataChapter(title = title.orEmpty(), startMs = startMs, lengthMs = lengthMs ?: 0L)

private fun BookContributorMeta.toContributorRef(): MetadataContributorRef =
    MetadataContributorRef(asin = key, name = name)

private fun SeriesMeta.toSeriesRef(): MetadataSeriesRef =
    MetadataSeriesRef(asin = key, title = title, sequence = sequence)
