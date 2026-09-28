package com.calypsan.listenup.server.ratings

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
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
 * metadata region); [BookExternalRatingRepository.recordFetch] stores that region on the row, and
 * the sweep reuses it on the next pass.
 */
internal class ExternalRatingsFetcher(
    private val registry: MetadataProviderRegistry,
    private val ratings: BookExternalRatingRepository,
    private val sourceSettings: RatingSourceSettings,
    private val books: BookRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * How many enabled sources were consulted ([tried]), and how many of those gave a definite
     * answer ([answered]) — a rating or a confident "no rating". `tried > answered` means at least
     * one enabled source failed.
     */
    data class Outcome(
        val tried: Int,
        val answered: Int,
    )

    /**
     * Runs every enabled [RatingSource] for [bookId] in [locale]. A book that no longer exists is a
     * harmless no-op [Outcome] of zero/zero. [refresh] bypasses each source's provider-side cache.
     */
    suspend fun fetch(
        bookId: BookId,
        locale: MetadataLocale,
        refresh: Boolean,
    ): Outcome {
        val identity = books.findById(bookId)?.toIdentity() ?: return Outcome(tried = 0, answered = 0)

        var tried = 0
        var answered = 0
        for (source in registry.capable<RatingSource>()) {
            if (!sourceSettings.isEnabled(source.ratingSource)) continue
            tried++
            if (runOne(source, bookId, identity, locale, refresh)) answered++
        }
        return Outcome(tried = tried, answered = answered)
    }

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
                                region = locale.region,
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
 * Projects a persisted book onto the [RatingSource] lookup key — the same asin/isbn/title/primary-
 * author/duration shape [com.calypsan.listenup.server.api.MetadataLookupServiceImpl] builds for the
 * phase-1 match scorer.
 */
private fun BookSyncPayload.toIdentity(): BookIdentity =
    BookIdentity(
        asin = asin,
        isbn = isbn,
        title = title,
        primaryAuthor =
            contributors.firstOrNull { ContributorRole.fromApiValue(it.role) == ContributorRole.AUTHOR }?.name,
        durationMs = totalDuration.takeIf { it > 0 },
    )
