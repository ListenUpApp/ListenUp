package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.dto.activity.ActivityType

/**
 * Longest idle stretch that still counts as the same sitting. Pausing to make coffee, take a call,
 * or walk between rooms does not end your evening with a book — but picking it up again after lunch
 * is a genuinely separate act worth its own line in the feed.
 */
private const val SAME_SITTING_GAP_MS = 60 * 60_000L

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
    return idleMs in 0..SAME_SITTING_GAP_MS
}
