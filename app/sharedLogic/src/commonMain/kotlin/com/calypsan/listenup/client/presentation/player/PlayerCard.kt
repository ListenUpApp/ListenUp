package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlayerPhase

/**
 * The card that takes the transport's place on Now Playing (spec P32, §3.7, §4.6, the Now Playing column of §5.6):
 * a problem with its ways out, the finished book, or the sleep timer's fade. Decided once so Android, iOS and web offer the same actions.
 */
sealed interface PlayerCard {
    /**
     * The book can't play. Platforms render [error]'s title (`player.title_<code>`) and cause (`player.cause_<code>`),
     * "Your place is saved." when [placeSaved], and "Reference {id}" when the error carries a correlation id.
     */
    data class Problem(
        val error: AppError,
        val primaryAction: PlayerCardAction,
        val secondaryAction: PlayerCardAction?,
        val placeSaved: Boolean,
        val tone: PlayerCardTone,
    ) : PlayerCard

    /** Played to the end (§3.7): when, how it was, and what comes next. */
    data class Finished(
        val info: FinishedInfo,
        val primaryAction: PlayerCardAction,
        val secondaryAction: PlayerCardAction?,
    ) : PlayerCard

    /**
     * The sleep timer has fired and the audio is fading (§5.6 "Sleep fading"). Platforms draw it in the sleep gold,
     * with one button, Keep listening ([PlayerCardAction.KeepListening]).
     */
    data object SleepFading : PlayerCard
}

/** What a card's button does. [PlayerViewModel.onCardAction] carries each one out; the words are each platform's. */
sealed interface PlayerCardAction {
    /** Re-probe and prepare again at the saved position. */
    data object Retry : PlayerCardAction

    /** Clear the failure; the book stays loaded, paused. */
    data object Dismiss : PlayerCardAction

    /** Stop and unload the book. */
    data object CloseBook : PlayerCardAction

    /** Go to sign-in. */
    data object SignIn : PlayerCardAction

    /** Go to the Inbox (admins, a held book). */
    data object OpenInbox : PlayerCardAction

    /** Resume after Android refused a background start. */
    data object Resume : PlayerCardAction

    /** Start playback with a tap the browser will accept (web, autoplay refused). */
    data object TapToPlay : PlayerCardAction

    /** Play this book again from the start (F3: never a different one). */
    data object ListenAgain : PlayerCardAction

    /** Play the next book in the series (§3.7). */
    data object PlayNextBook : PlayerCardAction

    /** Cancel the sleep timer during its fade; full volume returns (PT7). */
    data object KeepListening : PlayerCardAction
}

/** How a problem card is coloured: the error container, Book Detail's held amber, or a plain container. */
sealed interface PlayerCardTone {
    /** A failure the reader has to act on. */
    data object Error : PlayerCardTone

    /** Waiting in the Inbox. */
    data object Held : PlayerCardTone

    /** A refusal, a lapsed session, or a known "can't play yet". */
    data object Neutral : PlayerCardTone
}

/** Derives [PlayerCard]. An object, so Swift Export exposes it as `PlayerCards.shared`. */
object PlayerCards {
    /** The card for [state], or null when the transport shows. Pure. */
    fun of(state: PlayerUiState.Loaded): PlayerCard? =
        when (val phase = state.phase) {
            is PlayerPhase.Failed -> problem(phase.error, isFailure = true, primary = state.primary)
            is PlayerPhase.Unavailable -> problem(phase.reason, isFailure = false, primary = state.primary)
            PlayerPhase.Finished -> state.finished?.let(::finishedCard)
            PlayerPhase.SleepFading -> PlayerCard.SleepFading
            PlayerPhase.Playing, PlayerPhase.Paused, PlayerPhase.Restored, PlayerPhase.Preparing,
            PlayerPhase.Buffering, PlayerPhase.Reconnecting,
            -> null
        }

    /** True when Now Playing should close: nothing is loaded, or the loaded book was revoked (§5.6). Pure. */
    fun closesNowPlaying(state: PlayerUiState): Boolean =
        when (state) {
            PlayerUiState.NothingLoaded -> true
            is PlayerUiState.Loaded -> (state.phase as? PlayerPhase.Unavailable)?.reason is PlaybackError.NoLongerAvailable
        }

    private fun problem(
        error: AppError,
        isFailure: Boolean,
        primary: PrimaryControl,
    ): PlayerCard.Problem {
        val (first, second) = actionsFor(error, isFailure, primary)
        return PlayerCard.Problem(
            error = error,
            primaryAction = first,
            secondaryAction = second,
            placeSaved = error !is PlaybackError.NoLongerAvailable,
            tone =
                when {
                    error is BookError.HeldForReview -> PlayerCardTone.Held
                    isFailure && error.isFailureToFix() -> PlayerCardTone.Error
                    else -> PlayerCardTone.Neutral
                },
        )
    }

    private fun actionsFor(
        error: AppError,
        isFailure: Boolean,
        primary: PrimaryControl,
    ): Pair<PlayerCardAction, PlayerCardAction?> {
        val leaveIt = if (isFailure) PlayerCardAction.Dismiss else PlayerCardAction.CloseBook
        return when (error) {
            is AuthError.SessionExpired -> PlayerCardAction.SignIn to leaveIt
            is PlaybackError.NoLongerAvailable -> PlayerCardAction.CloseBook to null
            is BookError.HeldForReview ->
                if (primary == PrimaryControl.OpenInbox) {
                    PlayerCardAction.OpenInbox to PlayerCardAction.CloseBook
                } else {
                    PlayerCardAction.CloseBook to null
                }
            is PlaybackError.BlockedInBackground -> PlayerCardAction.Resume to null
            is PlaybackError.AutoplayBlocked -> PlayerCardAction.TapToPlay to null
            is PlaybackError.ServerUnreachable, is PlaybackError.OfflineNotDownloaded, is PlaybackError.UnsupportedFormat ->
                PlayerCardAction.Retry to PlayerCardAction.CloseBook
            else -> PlayerCardAction.Retry to leaveIt
        }
    }

    private fun finishedCard(info: FinishedInfo): PlayerCard.Finished =
        if (info.nextBook != null) {
            PlayerCard.Finished(info, PlayerCardAction.PlayNextBook, PlayerCardAction.ListenAgain)
        } else {
            PlayerCard.Finished(info, PlayerCardAction.ListenAgain, secondaryAction = null)
        }
}
