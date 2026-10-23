package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState

/**
 * What the mini player's second line says (spec §5.1 M6/M7, the mini column of §5.6), decided once so the
 * Android bars, the iOS accessory and the web bar agree. Each surface chooses the parts its width holds; the time
 * left is the surface's to add from [PlayerProgress], so this value never changes on a position tick.
 *
 * A chapter is named by the book's own title, never by our counter, which counts front matter and so can disagree
 * with the book's naming ("Chapter 14" shown beside "Ch. 16"). Only a chaptered book's untitled chapter, where there
 * is no name to disagree with, falls back to its number.
 */
sealed interface MiniPlayerLine {
    /**
     * Playing: "Bridge Four". [title] is the chapter's own title as the book names it, generic or not; null for a
     * chapterless book, which reads "Whole book", or an untitled chapter, which reads "Chapter [number]". [number]
     * is set only in that untitled case. [sleepArmed] says a timer is running; the surface shows its countdown,
     * `SleepCountdowns.remainingMs(...)`, where the line has its time left. The line carries no number of its own, so it
     * stays tick-free.
     */
    data class Chapter(
        val number: Int?,
        val title: String?,
        val sleepArmed: Boolean,
    ) : MiniPlayerLine

    /** Paused, or restored at a cold start: "Paused · Bridge Four". Parts as [Chapter]. */
    data class Paused(
        val number: Int?,
        val title: String?,
        val sleepArmed: Boolean,
    ) : MiniPlayerLine

    /** Casting or AirPlay: "Playing on Living Room · Bridge Four". Parts as [Chapter]. */
    data class Casting(
        val deviceName: String,
        val number: Int?,
        val title: String?,
    ) : MiniPlayerLine

    /** A book is being prepared: "Starting…" (§3.6). */
    data object Starting : MiniPlayerLine

    /** Stalled while buffering: "Loading…". */
    data object Loading : MiniPlayerLine

    /** The network dropped under a listen and the player is recovering inside its grace: "Reconnecting…". */
    data object Reconnecting : MiniPlayerLine

    /**
     * The sleep timer's fade is running: "Sleep timer · fading out". The mini player's main button is then
     * Keep listening, which cancels the timer and restores full volume (PT7, Simon 2026-10-14).
     */
    data object FadingOut : MiniPlayerLine

    /** Played to the end: "Finished" (§3.7). */
    data object Finished : MiniPlayerLine

    /**
     * The book can't play. [isTinted] marks a failure the reader has to act on; false for a refusal (autoplay,
     * background start), a lapsed session, and every known "can't play yet".
     */
    data class Problem(
        val error: AppError,
        val isTinted: Boolean,
    ) : MiniPlayerLine
}

/** Derives [MiniPlayerLine]. An object, so Swift Export exposes it as `MiniPlayerLines.shared.of(state:)`. */
object MiniPlayerLines {
    /** The mini player's line for [state]. Pure. */
    fun of(state: PlayerUiState.Loaded): MiniPlayerLine {
        val chapter = state.chapter
        val title = chapter.title.takeUnless { chapter.isWholeBook || it.isBlank() }
        val number = chapter.number.takeIf { !chapter.isWholeBook && title == null }
        val sleepArmed = state.sleepTimer is SleepTimerState.Active
        val casting = state.source as? AudioSource.Casting
        return when (val phase = state.phase) {
            is PlayerPhase.Failed -> {
                MiniPlayerLine.Problem(phase.error, isTinted = phase.error.isFailureToFix())
            }

            is PlayerPhase.Unavailable -> {
                MiniPlayerLine.Problem(phase.reason, isTinted = false)
            }

            PlayerPhase.Preparing -> {
                MiniPlayerLine.Starting
            }

            PlayerPhase.Buffering -> {
                MiniPlayerLine.Loading
            }

            PlayerPhase.Reconnecting -> {
                MiniPlayerLine.Reconnecting
            }

            PlayerPhase.SleepFading -> {
                MiniPlayerLine.FadingOut
            }

            PlayerPhase.Finished -> {
                MiniPlayerLine.Finished
            }

            PlayerPhase.Playing, PlayerPhase.Paused, PlayerPhase.Restored -> {
                when {
                    casting != null -> MiniPlayerLine.Casting(casting.deviceName, number, title)
                    phase == PlayerPhase.Playing -> MiniPlayerLine.Chapter(number, title, sleepArmed)
                    else -> MiniPlayerLine.Paused(number, title, sleepArmed)
                }
            }
        }
    }
}
