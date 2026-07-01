package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import kotlin.time.Instant

/**
 * Every server-side trigger that affects a user's stats. The sole input to [StatsRecorder.record] —
 * the single choke-point through which `user_stats`, the `public_profiles` projection, the per-
 * completion `book_reads` log, and stats-derived activity rows are written. Durable interface: this
 * taxonomy is designed to survive the future read-side ("one event log, four projections")
 * migration unchanged, even as the recorder's window-materialization steps shrink.
 */
sealed interface StatsEvent {
    /** The user this event's stats apply to. */
    val userId: String

    /**
     * A closed listening span committed (live listen tick / session-end push / seeded/imported
     * listen). [span] is the same payload [ListeningEventRepository.upsert] just persisted.
     */
    data class ListeningSessionClosed(
        override val userId: String,
        val span: ListeningEventSyncPayload,
    ) : StatsEvent

    /**
     * A book crossed unfinished → finished. [occurredAt] is the book's finished date (may be
     * backdated by an import or a manual edit) — it drives the `book_reads.finished_at` row, the
     * windowed recompute, and the `FINISHED_BOOK` activity's `occurred_at`, all from one timestamp.
     */
    data class BookCompleted(
        override val userId: String,
        val bookId: String,
        val occurredAt: Instant,
    ) : StatsEvent

    /**
     * A book was (re)started: either the user's first-ever position on it, or a finished book
     * reopened (a re-read). [isReread] distinguishes the two for the `STARTED_BOOK` activity row;
     * neither case touches `user_stats` or the `public_profiles` projection — starting a book moves
     * no windowed stat.
     */
    data class BookRestarted(
        override val userId: String,
        val bookId: String,
        val occurredAt: Instant,
        val isReread: Boolean,
    ) : StatsEvent

    /**
     * Bulk source rows already exist (ABS import / admin backfill) — recompute `user_stats` from
     * scratch and refresh the projection for [userId]. One [BulkRecompute] per affected user, never
     * one per imported row.
     */
    data class BulkRecompute(
        override val userId: String,
    ) : StatsEvent
}
