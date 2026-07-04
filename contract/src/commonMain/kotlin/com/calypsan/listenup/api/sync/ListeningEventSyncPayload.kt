package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for one closed listening span — a single uninterrupted play recorded
 * by the client and shipped to the server as an append-only event. The basis
 * of all P2 stats and history.
 *
 * Durations are derived, not stored: `wallSeconds = (endedAt - startedAt) / 1000`,
 * `audioSecondsCovered = (endPositionMs - startPositionMs) / 1000`. Storing both
 * invites them to drift.
 *
 * A span is by definition uninterrupted: a pause / sleep-timer / book-end / speed
 * change / user seek closes the current span and (optionally) opens a new one.
 * Each event therefore has a single `playbackSpeed`.
 *
 * `tz` is the IANA name the client supplied (e.g. `Europe/London`) at recording
 * time. Stats day-boundary math (streaks) runs in this TZ.
 *
 * Implements [Tombstoned] for uniform soft-delete routing; in P2 no tombstones
 * are emitted (append-only), but the substrate's machinery still requires the
 * shape.
 */
@Serializable
@SerialName("ListeningEventSyncPayload")
data class ListeningEventSyncPayload(
    override val id: String,
    val bookId: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val startedAt: Long,
    val endedAt: Long,
    val playbackSpeed: Float,
    val tz: String,
    val deviceLabel: String?,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : SyncPayload
