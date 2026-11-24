package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.server.db.sqldelight.Playback_positions
import kotlin.math.min

/**
 * One resolved progress row an ABS import intends to persist as a playback position — the batch
 * counterpart to a single [PlaybackPositionRepository.recordPosition] call's arguments. The importer
 * resolves every ABS `(user, item)` pair to one of these, then hands the whole list to
 * [PlaybackPositionRepository.recordAllForImport] so the writes commit in chunked transactions
 * instead of one read+write commit pair per row.
 *
 * [finishedAt] is when the source says the book was finished, if it says — it dates the finish in
 * place of [lastPlayedAt], exactly as a reader's picked day does on [PlaybackPositionRepository.recordPosition].
 */
data class ImportPositionWrite(
    val userId: String,
    val bookId: String,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val finished: Boolean,
    val playbackSpeed: Float,
    val currentChapterId: String?,
    val volumeBoostDb: Float = 0f,
    val measuredGainDb: Float? = null,
    val startedBookOccurredAt: Long? = null,
    val finishedAt: Long? = null,
)

/**
 * One user's in-progress position in a book, for its readership.
 *
 * @property userId Whose position this is.
 * @property positionMs Where they are, book-relative ms.
 * @property lastPlayedAt Epoch-ms they last played the book.
 */
data class InProgressPosition(
    val userId: String,
    val positionMs: Long,
    val lastPlayedAt: Long,
)

/**
 * How far into the future a client-reported `lastPlayedAt` or `finishedAt` is trusted, relative to the
 * server clock — generous for honest clock drift, but nothing further out survives the clamp in
 * [PlaybackPositionRepository.recordPosition] / [PlaybackPositionRepository.recordAllForImport]. Never
 * bounds the past: an offline-for-days device's old timestamp is untouched.
 */
internal const val SKEW_TOLERANCE_MS = 5 * 60 * 1000L

/**
 * The finish date a write may claim, or null when it claims none the server can use — the caller
 * then dates the finish by its `lastPlayedAt`, as it always has.
 *
 * Bounded above by the same skew ceiling as `lastPlayedAt`: the picker refuses future days, so a
 * later value is a fast device clock, and a finish dated in the future would put a book in a stats
 * window that has not happened yet. NOT bounded below by anything but the epoch: a reader may be
 * recording a book they finished years before ListenUp — or before this server had the book at
 * all — and a floor such as the book's `created_at` would refuse exactly what the picker exists
 * for. A non-positive value is the unset sentinel (an ABS backup's blank `finishedAt` parses to 0),
 * never a real day.
 */
internal fun resolveFinishedAt(
    finishedAt: Long?,
    nowMs: Long,
): Long? = finishedAt?.takeIf { it > 0 }?.let { min(it, nowMs + SKEW_TOLERANCE_MS) }

/**
 * The start day a finishing write may claim, or null when it claims none the server can use — the read
 * is then started as it always was (the listen-through's start, or derived from listening).
 *
 * Unlike [resolveFinishedAt] it is ignored, not clamped, when out of bounds: a start after the read's
 * [finishedAt], or beyond the skew ceiling, is not a day the picker could have produced (it refuses
 * both), so there is no nearby true value to clamp to. A non-positive value is the unset sentinel.
 */
internal fun resolveStartedAt(
    startedAt: Long?,
    finishedAt: Long,
    nowMs: Long,
): Long? = startedAt?.takeIf { it > 0 && it <= finishedAt && it <= nowMs + SKEW_TOLERANCE_MS }

internal fun Playback_positions.toSyncPayload(): PlaybackPositionSyncPayload =
    PlaybackPositionSyncPayload(
        id = id,
        bookId = book_id,
        positionMs = position_ms,
        lastPlayedAt = last_played_at,
        // `finished` is INTEGER 0/1 in SQLite; `playback_speed` is REAL (Double). Convert at the
        // boundary.
        finished = finished == 1L,
        playbackSpeed = playback_speed.toFloat(),
        currentChapterId = current_chapter_id,
        volumeBoostDb = volume_boost_db.toFloat(),
        measuredGainDb = measured_gain_db?.toFloat(),
        finishedAt = finished_at,
        hasCustomSpeed = has_custom_speed == 1L,
        hasCustomBoost = has_custom_boost == 1L,
        finishedCardDismissedAt = finished_card_dismissed_at,
        revision = revision,
        updatedAt = updated_at,
        createdAt = created_at,
        deletedAt = deleted_at,
    )
