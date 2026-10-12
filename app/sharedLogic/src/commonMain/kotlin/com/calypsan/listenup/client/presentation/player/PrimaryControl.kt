package com.calypsan.listenup.client.presentation.player

/** The one main button, decided once in shared code so every platform agrees (§4.6). */
sealed interface PrimaryControl {
    /** Audio is advancing. */
    data object Pause : PrimaryControl

    /** Paused, restored, or a background refusal ("Resume"). */
    data object Play : PrimaryControl

    /** Preparing or buffering: a spinner. Pressing it while buffering pauses. */
    data object Busy : PrimaryControl

    /** A transport, format or start failure, or a known "can't play yet". */
    data object Retry : PrimaryControl

    /** Finished: plays this book again from the start. Never starts a different book (F3). */
    data object ListenAgain : PrimaryControl

    /** Session expired with nothing downloaded. */
    data object SignIn : PrimaryControl

    /** Held, for an admin. */
    data object OpenInbox : PrimaryControl

    /** Revoked, or held for a member. */
    data object Close : PrimaryControl

    /** Web autoplay was refused: Play, drawn with a ring. */
    data object TapToPlay : PrimaryControl
}
