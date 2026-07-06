package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload

/**
 * Converts an Audiobookshelf [AbsSession] to a [ListeningEventSyncPayload].
 *
 * **Stable id:** `"abs:<sessionId>"` — re-importing the same backup upserts the same row
 * (idempotent, no duplicate history).
 *
 * **timeListening fidelity:** `endedAt = startedAt + timeListening*1000` so the stats backfill's
 * `(endedAt - startedAt) / 1000` listen-seconds equals ABS's actual `timeListening`. Paused /
 * open sessions never inflate totals; wall-clock span is irrelevant.
 *
 * `tz` defaults to `"UTC"` — ABS sessions don't carry a reliable IANA time zone; the stats
 * day-boundary math (streaks) uses it, and UTC is a safe deterministic default. Consequence:
 * imported history is day-bucketed in UTC, so a session late in the user's local evening can
 * land on the "wrong" local day and slightly shift imported streaks. Considered and rejected
 * (BACKUP-04): threading the source offset through — ABS timestamps carry at most a fixed
 * numeric offset (usually +00:00), not the listener's IANA zone, so the plumbing
 * (parseAbsTimestampMs return shape, AbsSession field, reader) would buy no real fidelity.
 */
internal class SessionConverter {
    fun toEvent(
        session: AbsSession,
        bookId: String,
    ): ListeningEventSyncPayload {
        val listenMs = (session.timeListeningSeconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(0L)
        val startMs = (session.startPositionSeconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(0L)
        val endMs = (session.endPositionSeconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(startMs)
        return ListeningEventSyncPayload(
            id = "abs:${session.id}",
            bookId = bookId,
            startPositionMs = startMs,
            endPositionMs = endMs,
            startedAt = session.startedAtMs,
            endedAt = session.startedAtMs + listenMs,
            playbackSpeed = session.playbackSpeed,
            tz = DEFAULT_TZ,
            deviceLabel = session.deviceLabel ?: DEFAULT_DEVICE,
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        const val DEFAULT_TZ = "UTC"
        const val DEFAULT_DEVICE = "Audiobookshelf"
    }
}
