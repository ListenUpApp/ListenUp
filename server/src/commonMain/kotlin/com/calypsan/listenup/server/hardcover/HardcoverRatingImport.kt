package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.ListenerRatingSource
import com.calypsan.listenup.domain.ListenerRatingLimits
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.BookRatingRepository
import com.calypsan.listenup.server.sync.ratingSourceOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * Hardcover's `user_books.rating` (0.5 steps; null or 0 when unrated) as ListenUp half stars
 * (2..10), or null for no rating. Hardcover's half-star rating is one star here: ListenUp's floor.
 */
internal fun hardcoverHalfStars(rating: Double?): Int? =
    rating
        ?.takeIf { it > 0.0 }
        ?.let { (it * 2).roundToInt().coerceIn(ListenerRatingLimits.MIN_HALF_STARS, ListenerRatingLimits.MAX_HALF_STARS) }

/**
 * Brings a listener's own Hardcover ratings into their ListenUp ratings, filling gaps only: a rating
 * set or edited in ListenUp is never overwritten; one imported from Hardcover follows Hardcover until
 * the listener touches it; and one the listener cleared stays cleared until Hardcover's rating changes.
 *
 * Hardcover has no `rated_at`, and its `updated_at` moves on any shelf edit, so "changed" is decided
 * against `book_ratings.hardcover_half_stars`: the value the pull last saw for the pair (0 = none,
 * NULL = never looked). Writes go through [BookRatingRepository], so they bump the revision and reach
 * every device like any rating — and, like any rating, post nothing to the feed and notify no one.
 */
class HardcoverRatingImport(
    private val sql: ListenUpDatabase,
    private val ratings: BookRatingRepository,
) {
    /** Applies one pulled page: each entry resolved to a library book gets [apply]. The first failure fails the page. */
    suspend fun applyPage(
        userId: String,
        page: List<HardcoverShelfEntry>,
        resolved: Map<Long, ShelfResolution>,
    ): AppResult<Unit> {
        for (entry in page) {
            val bookId = resolved[entry.userBookId]?.bookId ?: continue
            // A rating that isn't public on Hardcover is "Hardcover has none": never imported, and an
            // imported one that turns private is cleared while it is still Hardcover's.
            val outcome = apply(userId, bookId, entry.sharedRatingHalfStars)
            if (outcome is AppResult.Failure) return outcome
        }
        return done
    }

    /** Reconciles [userId]'s rating of [bookId] with Hardcover's [hcHalfStars] (null = unrated), then records it as seen. */
    suspend fun apply(
        userId: String,
        bookId: String,
        hcHalfStars: Int?,
    ): AppResult<Unit> {
        val row = suspendTransaction(sql) { sql.bookRatingsQueries.selectForImport(bookId, userId).executeAsOneOrNull() }
        val outcome =
            when {
                row == null -> {
                    hcHalfStars?.let { write(userId, bookId, it, wireId = Uuid.random().toString(), note = null) } ?: done
                }

                row.deleted_at != null -> {
                    val seen = row.hardcover_half_stars?.toInt()
                    if (hcHalfStars != null && seen != null && seen != hcHalfStars) {
                        write(userId, bookId, hcHalfStars, wireId = row.id, note = null)
                    } else {
                        done
                    }
                }

                ratingSourceOf(row.source) == ListenerRatingSource.LISTENUP -> {
                    done
                }

                hcHalfStars == null -> {
                    ratings.clear(bookId = bookId, userId = userId)
                }

                hcHalfStars != row.half_stars.toInt() -> {
                    write(userId, bookId, hcHalfStars, wireId = row.id, note = row.note)
                }

                else -> {
                    done
                }
            }
        if (outcome is AppResult.Failure) return outcome
        suspendTransaction(sql) {
            sql.bookRatingsQueries.setHardcoverSeen(
                hardcover_half_stars = (hcHalfStars ?: 0).toLong(),
                book_id = bookId,
                user_id = userId,
            )
        }
        return done
    }

    private suspend fun write(
        userId: String,
        bookId: String,
        halfStars: Int,
        wireId: String,
        note: String?,
    ): AppResult<Unit> =
        ratings
            .upsert(
                BookRatingSyncPayload(
                    id = wireId,
                    bookId = bookId,
                    userId = userId,
                    halfStars = halfStars,
                    note = note,
                    ratedAt = 0L,
                    updatedAt = 0L,
                    revision = 0L,
                    source = ListenerRatingSource.HARDCOVER,
                ),
            ).map { }

    private companion object {
        val done: AppResult<Unit> = AppResult.Success(Unit)
    }
}
