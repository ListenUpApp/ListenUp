package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.activity.RealListen

/**
 * Collapse consecutive listening sessions on the same book into one entry per sitting.
 *
 * The server records one activity per closed playback span, so a single evening with a book —
 * paused for the kettle, resumed, paused again — arrives as a wall of entries, several of them
 * seconds long. That is noise on the feed, which is the app's social surface: the interesting fact
 * is "Simon listened to Dungeon Crawler Carl for an hour", not the shape of his interruptions.
 *
 * Merging happens at READ time, deliberately. The `activities` table is append-only by design
 * (`ActivitySyncRepository` advances only revision/updatedAt on re-upsert, never domain fields), and
 * every viewer's client runs this over whatever it displays — so the feed reads correctly for other
 * people's sessions too, without mutating a log or touching the sync contract.
 *
 * Only *adjacent* rows merge, so any other activity between two sittings (finishing the book,
 * a milestone) separates them. Input is expected most-recent-first, matching the feed's
 * `occurred_at DESC` ordering; the surviving entry keeps the newest [ActivityUiModel.occurredAt] so
 * it still sorts as recent, and carries the summed duration.
 *
 * One consequence worth knowing: merging is per-page, so a sitting split across a pagination
 * boundary stays split. That is a cosmetic edge, not a correctness one — and it beats mutating
 * durable rows to fix it.
 */
internal fun List<ActivityUiModel>.coalesceListeningSessions(): List<ActivityUiModel> =
    fold(mutableListOf()) { merged: MutableList<ActivityUiModel>, activity ->
        val previous = merged.lastOrNull()
        if (previous != null && previous.continuesInto(activity)) {
            // `activity` is the OLDER half of the sitting; keep the newer entry's identity and
            // timestamp, and extend it backwards by the older span's duration.
            merged[merged.lastIndex] = previous.copy(durationMs = previous.durationMs + activity.durationMs)
        } else {
            merged.add(activity)
        }
        merged
    }

/**
 * True when [older] is the same sitting as this (newer) entry: both are listening sessions on the
 * same book by the same person, with only a short idle stretch between them.
 *
 * The gap measured is END-of-older to START-of-newer. The newer entry's `occurredAt` is when its
 * span *finished*, so its start is `occurredAt - durationMs`; comparing the raw timestamps instead
 * would count the newer span's own length as idle time and wrongly split long sessions.
 */
private fun ActivityUiModel.continuesInto(older: ActivityUiModel): Boolean {
    if (type != ActivityType.LISTENING_SESSION || older.type != ActivityType.LISTENING_SESSION) return false
    if (bookId == null || bookId != older.bookId) return false
    if (userId != older.userId) return false
    val newerSpanStart = occurredAt - durationMs
    val idleMs = newerSpanStart - older.occurredAt
    return idleMs in 0..RealListen.SITTING_GAP_MS
}

/**
 * Drop listening sittings too short to be news — the eight-second tap, the wrong book opened by
 * mistake. Run AFTER [coalesceListeningSessions]: a sitting of several short fragments is judged by
 * its total, so a stop-start evening still shows while a lone accidental tap does not.
 *
 * The listening itself still counts everywhere else (stats, streaks, progress); this only decides
 * what the feed announces. [RealListen.THRESHOLD_MS] is the same line the server uses for "started
 * a book", so the two can never disagree about what counts.
 */
internal fun List<ActivityUiModel>.withoutFleetingSittings(): List<ActivityUiModel> =
    filterNot { it.type == ActivityType.LISTENING_SESSION && it.durationMs < RealListen.THRESHOLD_MS }
