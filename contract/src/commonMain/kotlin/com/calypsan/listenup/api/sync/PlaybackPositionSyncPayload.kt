package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a user's playback position in one book — the cross-device
 * resume point. A per-user syncable domain (Playback P1).
 *
 * `lastPlayedAt` is the wall-clock of the actual listening moment this position
 * represents; it is the **conflict key** — the server keeps the write with the
 * greatest `lastPlayedAt`, so a stale offline write never clobbers a fresher
 * position from another device.
 *
 * Implements [Tombstoned] for uniform soft-delete routing (a position is
 * tombstoned only when its book is deleted).
 *
 * The wire payload deliberately does not carry `userId` — the client only ever
 * receives its own rows; userId is a server-side routing/storage concern.
 *
 * [finishedAt], [hasCustomSpeed] and [hasCustomBoost] are the cross-device fields: without
 * them a receiving device learns *that* a book was finished but not *when*, and stores a
 * per-book speed or boost that it is then required to ignore.
 */
@Serializable
@SerialName("PlaybackPositionSyncPayload")
data class PlaybackPositionSyncPayload(
    override val id: String,
    val bookId: String,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val finished: Boolean,
    val playbackSpeed: Float,
    val currentChapterId: String?,
    @SerialName("volumeBoostDb") val volumeBoostDb: Float = 0f,
    @SerialName("measuredGainDb") val measuredGainDb: Float? = null,
    /**
     * When this book was first completed, epoch ms; null when it was never finished (or was
     * finished before this field existed). Sticky — a re-finish keeps the first completion's
     * timestamp, matching the client's `PlaybackPositionEntity.finishedAt`. Feeds the client's
     * streak day-set and "finished in <Month Year>" without a round-trip.
     *
     * Carried as a column on the position rather than derived server-side from the `book_reads`
     * log, because the sync-pull path builds payloads row-by-row from the generated row type and
     * a per-row lookup into another table would restructure that read path. The `book_reads` log
     * remains the authoritative per-completion history — a last-write-wins column cannot
     * reconstruct re-reads. This field exists so a client that has never seen the book finished
     * still learns *when*.
     */
    @SerialName("finishedAt") val finishedAt: Long? = null,
    /**
     * True when the listener explicitly chose [playbackSpeed] for this book, rather than
     * inheriting the account default. Synced rather than kept device-local because the payload
     * already carries the *value*; sending it without the flag that makes it usable is the worst
     * of both worlds — bytes on the wire the receiver is required to ignore. `PlaybackPreparer`
     * gates the per-book speed on exactly this flag.
     */
    @SerialName("hasCustomSpeed") val hasCustomSpeed: Boolean = false,
    /** True when the listener explicitly chose [volumeBoostDb] for this book. See [hasCustomSpeed]. */
    @SerialName("hasCustomBoost") val hasCustomBoost: Boolean = false,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : SyncPayload
