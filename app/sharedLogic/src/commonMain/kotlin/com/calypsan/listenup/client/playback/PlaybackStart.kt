package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter

/**
 * Where a load starts, as the listener asked for it. The preparer turns it into a book position only after
 * reconciling this device's saved position with the server's (newer wins), so nothing asked for before Play
 * can resume from a stale local row and overwrite another device's progress.
 */
sealed interface PlaybackStart {
    /** The reconciled saved position, backed up by auto-rewind (a plain Play, a Retry, Listen again). */
    data object Resume : PlaybackStart

    /** Exactly [positionMs], book-relative: a place the listener picked (a chapter tap, the scrubber). */
    data class At(
        val positionMs: Long,
    ) : PlaybackStart

    /**
     * [moves] made from where the listener is (skips, previous/next chapter) on a book not yet prepared,
     * replayed in order from the reconciled saved position, with no auto-rewind.
     */
    data class Moved(
        val moves: List<PlaybackMove>,
    ) : PlaybackStart
}

/** One relative move, measured from wherever the listener is when it is applied. */
sealed interface PlaybackMove {
    /** [deltaMs] of book time: negative is back. Already scaled by the speed at the moment of the skip. */
    data class Skip(
        val deltaMs: Long,
    ) : PlaybackMove

    /** The current chapter's start once it has run for more than 3 s, else the previous chapter's (§3.3). */
    data object PreviousChapter : PlaybackMove

    /** The next chapter's start; nothing at the last chapter. */
    data object NextChapter : PlaybackMove
}

/**
 * Where [moves] land from [fromMs] in a book of [durationMs] with [chapters]. Pure; the one replay, used both
 * for what a not-prepared book shows and, inside the preparer, for where it starts. Clamped into the book.
 */
internal fun landingMs(
    fromMs: Long,
    moves: List<PlaybackMove>,
    chapters: List<Chapter>,
    durationMs: Long,
): Long {
    val lastMs = (durationMs - 1).coerceAtLeast(0L)
    return moves
        .fold(fromMs.coerceIn(0L, lastMs)) { at, move ->
            when (move) {
                is PlaybackMove.Skip -> at + move.deltaMs
                PlaybackMove.PreviousChapter -> ChapterNavigation.previousStartMs(at, chapters)
                PlaybackMove.NextChapter -> ChapterNavigation.nextStartMs(at, chapters) ?: at
            }.coerceIn(0L, lastMs)
        }
}

/** This start with [move] added: a picked place moves at once (it is already absolute); anything else is replayed. */
internal fun PlaybackStart.then(
    move: PlaybackMove,
    chapters: List<Chapter>,
    durationMs: Long,
): PlaybackStart =
    when (this) {
        PlaybackStart.Resume -> {
            PlaybackStart.Moved(listOf(move))
        }

        is PlaybackStart.Moved -> {
            PlaybackStart.Moved(moves + move)
        }

        is PlaybackStart.At -> {
            PlaybackStart.At(
                landingMs(fromMs = positionMs, moves = listOf(move), chapters = chapters, durationMs = durationMs),
            )
        }
    }
