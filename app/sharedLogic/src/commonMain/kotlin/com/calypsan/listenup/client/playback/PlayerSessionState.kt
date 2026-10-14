package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId

/** The session's one public state. Never re-emits on a position tick. */
sealed interface PlayerSessionState {
    /** Launch, before the persisted loaded book is known. */
    data object Restoring : PlayerSessionState

    /** Nothing is loaded. */
    data object NothingLoaded : PlayerSessionState

    /**
     * [bookId] is loaded in [phase]. [isEngineBound] is true while the engine holds this book, so its
     * chapters, position and speed are the engine's; otherwise they are Room's and the saved position's.
     */
    data class Loaded(
        val bookId: BookId,
        val phase: PlayerPhase,
        val isEngineBound: Boolean,
    ) : PlayerSessionState
}
