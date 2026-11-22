package com.calypsan.listenup.api.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether a sitting is where someone started the book: not at all, for the first time, or over again.
 * A newer value an older client has never heard of reads as [NONE] (the property default, under
 * `contractJson`'s `coerceInputValues`), so a sitting still shows, just without the start folded in.
 */
@Serializable
enum class SittingStart {
    /** The book was already under way when this sitting began. */
    @SerialName("none")
    NONE,

    /** The listener's first time through the book began in this sitting. */
    @SerialName("first")
    FIRST,

    /** A re-read of a book they had finished began in this sitting. */
    @SerialName("reread")
    REREAD,
}

/**
 * One sitting: a person's unbroken time with one book, as the Activity feed shows it — the `sittings`
 * sync row. Spans of theirs on the book with idle gaps of at most an hour between them are one
 * sitting (see `RealListen`), so pausing for the kettle never makes a second row.
 *
 * Written only by the server, as a projection of what it knows about that listening, and rewritten
 * in place as more arrives: a sitting grows while the person keeps listening and keeps its [id]. Two
 * sittings that late evidence joins become one, and the other is tombstoned. Only a real listen (at
 * least a minute) has a row. Readable by everyone who can open [bookId].
 *
 * Never a source for stats or streaks: those read the listening events themselves.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("SittingSyncPayload")
data class SittingSyncPayload(
    /** Opaque sync identity, stable for the life of the sitting. */
    @SerialName("id") override val id: String,
    /** Who listened. */
    @SerialName("userId") val userId: String,
    /** What they listened to. */
    @SerialName("bookId") val bookId: String,
    /** When the sitting's first stretch of listening began, epoch ms. */
    @SerialName("startedAt") val startedAt: Long,
    /** When its last stretch ended, epoch ms; never later than the server's clock when it was written. */
    @SerialName("endedAt") val endedAt: Long,
    /** Time actually listened, idle gaps left out and time two devices played at once counted once. */
    @SerialName("listenedMs") val listenedMs: Long,
    /** Whether the book was started in this sitting. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("beganRead")
    val beganRead: SittingStart = SittingStart.NONE,
    /** True when the book was finished in this sitting. */
    @SerialName("finishedRead") val finishedRead: Boolean = false,
    /** Sync revision. */
    @SerialName("revision") override val revision: Long,
    /** Tombstone instant, else null. */
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
