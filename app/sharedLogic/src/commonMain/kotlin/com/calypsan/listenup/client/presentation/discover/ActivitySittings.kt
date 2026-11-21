package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.activity.ListenSpan
import com.calypsan.listenup.api.dto.activity.RealListen
import com.calypsan.listenup.api.dto.activity.groupIntoSittings

/**
 * The feed's listening rows as sittings: one line per sitting with a book, and none for a sitting too
 * short to be news.
 *
 * The server records one activity per closed playback span, so a single evening with a book (paused for
 * the kettle, resumed, paused again) arrives as a wall of entries, several of them seconds long. The
 * interesting fact is "Simon listened to Dungeon Crawler Carl for an hour", not the shape of his
 * interruptions; and an eight-second tap on the wrong book is not something to announce at all.
 *
 * What a sitting is, and how long one must be, is [groupIntoSittings] and [RealListen]: the same rule
 * Discover's "what others are listening to" reads on the server, so the two surfaces never disagree about
 * who really listened. A sitting of several short fragments is judged by its total.
 *
 * Each sitting keeps its newest row, in that row's place, carrying the sitting's listened time (time two
 * devices played at once counted once, see [com.calypsan.listenup.api.dto.activity.Sitting.listenedMs]);
 * the rest of the sitting's rows go. A listening row with no book is judged alone. Rows that are not listening
 * pass through untouched.
 *
 * Merging happens at READ time, deliberately. The `activities` table is append-only by design
 * (`ActivitySyncRepository` advances only revision/updatedAt on re-upsert, never domain fields), and
 * every viewer's client runs this over whatever it displays, so the feed reads correctly for other
 * people's sessions too, without mutating a log or touching the sync contract. Merging is per page, so a
 * sitting split across a pagination boundary stays split: a cosmetic edge, and it beats mutating durable
 * rows to fix it.
 */
internal fun List<ActivityUiModel>.asSittings(): List<ActivityUiModel> {
    val sittings =
        mapNotNull { activity ->
            activity.listenSpan()?.let { activity to it }
        }.groupIntoSittings { it.second }
    val shownInPlaceOf: Map<String, ActivityUiModel?> =
        sittings
            .flatMap { sitting ->
                val newest = sitting.parts.maxBy { (activity, _) -> activity.occurredAt }.first
                val shown = newest.copy(durationMs = sitting.listenedMs).takeIf { sitting.isRealListen }
                sitting.parts.map { (activity, _) -> activity.id to shown?.takeIf { activity.id == newest.id } }
            }.toMap()
    return mapNotNull { activity ->
        when {
            activity.id in shownInPlaceOf -> shownInPlaceOf[activity.id]
            activity.isBooklessFleetingListen() -> null
            else -> activity
        }
    }
}

/** A listening row with no book has nothing to join, so it is judged alone: under a real listen, it is not news. */
private fun ActivityUiModel.isBooklessFleetingListen(): Boolean =
    type == ActivityType.LISTENING_SESSION && bookId == null && durationMs < RealListen.THRESHOLD_MS

/**
 * This row as a span of listening, or null when it is not one. A listening row's `occurredAt` is when its
 * span finished, so the span began [ActivityUiModel.durationMs] earlier.
 */
private fun ActivityUiModel.listenSpan(): ListenSpan? {
    val book = bookId ?: return null
    if (type != ActivityType.LISTENING_SESSION) return null
    return ListenSpan(userId = userId, bookId = book, startedAtMs = occurredAt - durationMs, endedAtMs = occurredAt)
}
