package com.calypsan.listenup.server.ratings

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.metadata.spi.RatingSourceAvailability
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Clock

private val logger = loggerFor<ExternalRatingsFetcher>()

/**
 * Fetches every enabled outside rating for one book and stores it. Each source runs on its own: one
 * failing never stops another, and a failure is recorded as that source's health (in
 * [RatingSourceSettings], per source — not derived from `book_external_ratings` rows, which would be
 * blind to a source that has never once succeeded) rather than thrown. Returns how many sources
 * answered (a rating or a confident "no rating"), and how many were tried, so the admin refresh can
 * tell "all failed" from "nothing to rate".
 *
 * Three call sites: applying a metadata match, the nightly sweep, and an admin's explicit refresh
 * request — each hands this the [locale] it wants the fetch run in (the server's configured
 * metadata region). A source may answer from a *different* store than the one requested (Audible
 * is region-locked — see [com.calypsan.listenup.server.services.MetadataService.getBookInAnyRegion]),
 * so [BookExternalRatingRepository.recordFetch] stores the region the source's [ExternalRatingMeta]
 * says actually answered, not the requested [locale] — and the sweep reuses that stored region on
 * the next pass, converging on the right store instead of retrying the wrong one forever.
 */
open class ExternalRatingsFetcher(
    private val registry: MetadataProviderRegistry,
    private val ratings: BookExternalRatingRepository,
    private val sourceSettings: RatingSourceSettings,
    private val books: BookRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * How many runnable (enabled, unpaused, available) sources were consulted ([tried]), and how many of those gave a definite
     * answer ([answered]) — a rating or a confident "no rating". `tried > answered` means at least
     * one enabled source failed.
     */
    data class Outcome(
        val tried: Int,
        val answered: Int,
    )

    /**
     * Runs every runnable [RatingSource] among [sources] (all of them by default) for [bookId] in
     * [locale], recording an attempt for each source it runs. A book that no longer exists is a
     * harmless no-op [Outcome] of zero/zero. [refresh] bypasses each source's provider-side cache.
     * The backfill narrows [sources] to the ones that have never tried the book.
     */
    open suspend fun fetch(
        bookId: BookId,
        locale: MetadataLocale,
        refresh: Boolean,
        sources: Set<ExternalRatingSource> = ExternalRatingSource.entries.toSet(),
    ): Outcome {
        val identity = books.findById(bookId)?.toIdentity() ?: return Outcome(tried = 0, answered = 0)

        var tried = 0
        var answered = 0
        for (source in registry.capable<RatingSource>()) {
            if (source.ratingSource !in sources || !canRun(source)) continue
            tried++
            if (runOne(source, bookId, identity, locale, refresh)) answered++
            // Remembered per source, regardless of outcome — a book a source fails (or confidently
            // has no rating for) never earns a book_external_ratings row, so without this the nightly
            // sweep would put it right back at the front of the queue, and the backfill would offer
            // it to that source again. See ExternalRatingAttempts.sq.
            ratings.recordAttempt(bookId.value, source.ratingSource, clock.now().toEpochMilliseconds())
        }
        return Outcome(tried = tried, answered = answered)
    }

    /** The sources that can run right now: enabled, not paused, and available. */
    suspend fun runnableSources(): Set<ExternalRatingSource> =
        registry
            .capable<RatingSource>()
            .filter { canRun(it) }
            .map { it.ratingSource }
            .toSet()

    /**
     * Re-fetches [bookId] in the region its rating was last found in (see [localeFor]), falling back
     * to the default market for a book never rated — an admin's refresh must not swap a UK rating
     * for a US one just because the request carries no region.
     */
    suspend fun refresh(bookId: BookId): Outcome = fetch(bookId, ratings.localeFor(bookId.value), refresh = true)

    /** A disabled, paused or unavailable source is skipped outright: not tried, no failure recorded. */
    private suspend fun canRun(source: RatingSource): Boolean =
        sourceSettings.isEnabled(source.ratingSource) &&
            sourceSettings.pausedUntil(source.ratingSource, clock.now().toEpochMilliseconds()) == null &&
            source.availability() is RatingSourceAvailability.Available

    /** Runs [source] contained: a thrown fault or a typed failure is recorded as health, never re-thrown. */
    private suspend fun runOne(
        source: RatingSource,
        bookId: BookId,
        identity: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): Boolean {
        val now = clock.now().toEpochMilliseconds()
        return runCatchingCancellable { source.getRating(identity, locale, refresh) }.fold(
            onSuccess = { result ->
                when (result) {
                    is AppResult.Success -> {
                        result.data?.let { meta ->
                            ratings.recordFetch(
                                bookId = bookId.value,
                                source = source.ratingSource,
                                average = meta.average,
                                count = meta.count,
                                region = meta.region,
                                fetchedAt = now,
                            )
                        }
                        // Success(null): a confident catalog miss — leave any existing row as it is, but
                        // the source did answer, so it counts toward health the same as a rating would.
                        sourceSettings.recordSuccess(source.ratingSource, now)
                        true
                    }

                    is AppResult.Failure -> {
                        logger.warn {
                            "external rating: ${source.ratingSource} failed for ${bookId.value} " +
                                "(${result.error.code}): ${result.error.debugInfo}"
                        }
                        sourceSettings.recordFailure(source.ratingSource, result.error.message, now)
                        false
                    }
                }
            },
            onFailure = { e ->
                logger.warn(e) { "external rating: ${source.ratingSource} threw for ${bookId.value}" }
                sourceSettings.recordFailure(source.ratingSource, e.message ?: "Unknown error", now)
                false
            },
        )
    }
}

/**
 * [bookId]'s most recently fetched source's region, or [default] when it has never had a live row
 * — reused by the nightly sweep ([com.calypsan.listenup.server.scheduler.ExternalRatingsSweepTask])
 * so a repeat fetch runs in the same region a rating was last found in, rather than always falling
 * back to the server's default market.
 */
internal suspend fun BookExternalRatingRepository.localeFor(
    bookId: String,
    default: MetadataLocale = MetadataLocale.DEFAULT,
): MetadataLocale = regionForBook(bookId)?.let { MetadataLocale(it) } ?: default

/**
 * Projects a persisted book onto the [RatingSource] lookup key — the same asin/isbn/title/primary-
 * author/duration shape [com.calypsan.listenup.server.api.MetadataLookupServiceImpl] builds for the
 * phase-1 match scorer.
 */
private fun BookSyncPayload.toIdentity(): BookIdentity =
    BookIdentity(
        // Blank is absent: a source keyed on either must fall through, not look up "".
        asin = asin?.takeIf { it.isNotBlank() },
        isbn = isbn?.takeIf { it.isNotBlank() },
        title = title,
        primaryAuthor =
            contributors.firstOrNull { ContributorRole.fromApiValue(it.role) == ContributorRole.AUTHOR }?.name,
        durationMs = totalDuration.takeIf { it > 0 },
    )
