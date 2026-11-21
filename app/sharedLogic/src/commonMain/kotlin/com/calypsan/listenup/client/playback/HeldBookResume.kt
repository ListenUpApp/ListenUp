package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.core.BookId
import kotlin.math.abs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** How far a newer row must be from the paused playhead before Play moves to it; closer is the same place. */
internal const val NEWER_POSITION_TOLERANCE_MS = 5_000L

/** Signed stream URLs this close to lapsing count as lapsed: they would die a few minutes into the listen. */
internal const val STREAM_URL_EXPIRY_MARGIN_MS = 5 * 60_000L

/** How [PlayerSession] resumes a book the engine holds, paused. */
internal sealed interface ResumePlan {
    /** A newer place from another device finished the book: a load decides it, as Play on a finished book does. */
    data object DecideFinished : ResumePlan

    /** The stream's signed URLs have lapsed: prepare the book again at [positionMs], which signs fresh ones. */
    data class Reprepare(
        val positionMs: Long,
    ) : ResumePlan

    /** Play the engine as it is, after a seek to [seekToMs] when it is set. */
    data class Resume(
        val seekToMs: Long?,
    ) : ResumePlan
}

/**
 * Decides how [PlayerSession] resumes a book the engine holds, paused, which never prepares it ([plan]). Touched only
 * on the player scope.
 *
 * Another device may have listened on since this one paused. Sync wrote its row into Room, but the engine never
 * heard: resuming from the old playhead would make this device's next save the newest place everywhere and
 * overwrite that progress. So a newer row moves the engine there first. No network on the tap: the row is what sync
 * already delivered. And a stream whose signed URLs have lapsed (a tab left open overnight) would fail at its next
 * request, so it is prepared again instead.
 */
internal class HeldBookResume(
    private val engine: PlayerEngine,
    private val engineBound: StateFlow<Boolean>,
    private val savedPosition: StateFlow<PlaybackPosition?>,
    private val nowMillis: () -> Long,
) {
    /**
     * The newest position-row stamp this device accounts for on the engine-bound book: the row it bound with, then
     * its own saves. Null while the engine holds nothing for the stage.
     */
    private var ownPositionStamp: Long? = null

    /**
     * Keep [ownPositionStamp], for the session's lifetime. A row that changes while the engine plays, or that lands
     * within [NEWER_POSITION_TOLERANCE_MS] of the playhead, is this device's own save (the periodic tick, the pause,
     * a seek). A row that lands far from a paused playhead was written elsewhere and synced in: it is left newer.
     */
    suspend fun accountForOwnPositionWrites() {
        combine(engineBound, savedPosition) { bound, row -> bound to row }.collect { (bound, row) ->
            val own = ownPositionStamp
            ownPositionStamp =
                when {
                    !bound -> null
                    own == null -> row?.effectiveLastPlayedAtMs ?: Long.MIN_VALUE
                    row != null && isOwnPositionWrite(row) -> maxOf(own, row.effectiveLastPlayedAtMs)
                    else -> own
                }
        }
    }

    /**
     * How to resume [bookId] in a book of [durationMs]: from [startAtMs] when the listener picked a place, else from
     * a newer place another device saved, else from the playhead.
     */
    fun plan(
        bookId: BookId,
        startAtMs: Long?,
        durationMs: Long,
    ): ResumePlan {
        val newer = if (startAtMs == null) newerPositionFromElsewhere(bookId) else null
        if (newer?.isFinished == true) return ResumePlan.DecideFinished
        if (newer != null) ownPositionStamp = newer.effectiveLastPlayedAtMs
        val target = (startAtMs ?: newer?.positionMs)?.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        return if (streamUrlsHaveLapsed(bookId)) {
            ResumePlan.Reprepare(target ?: engine.positionMs.value)
        } else {
            ResumePlan.Resume(seekToMs = target)
        }
    }

    /** The book's Room row when it is newer than this device's own place and elsewhere in the book, else null. */
    private fun newerPositionFromElsewhere(bookId: BookId): PlaybackPosition? {
        val row = savedPosition.value?.takeIf { it.bookId == bookId.value } ?: return null
        val own = ownPositionStamp ?: return null
        if (row.effectiveLastPlayedAtMs <= own) return null
        val elsewhere = abs(row.positionMs - engine.positionMs.value) > NEWER_POSITION_TOLERANCE_MS
        return row.takeIf { row.isFinished || elsewhere }
    }

    /** Whether the engine streams [bookId] from signed URLs that have lapsed, or will within the margin. */
    private fun streamUrlsHaveLapsed(bookId: BookId): Boolean {
        val held = engine.timeline.value?.takeIf { it.bookId == bookId } ?: return false
        val expiresAt = held.urlsExpireAtMs ?: return false
        return !held.isFullyDownloaded && nowMillis() >= expiresAt - STREAM_URL_EXPIRY_MARGIN_MS
    }

    private fun isOwnPositionWrite(row: PlaybackPosition): Boolean =
        engine.phase.value == EnginePhase.PLAYING ||
            engine.phase.value == EnginePhase.BUFFERING ||
            abs(row.positionMs - engine.positionMs.value) <= NEWER_POSITION_TOLERANCE_MS
}
