package com.calypsan.listenup.client.navigation

import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PlayerViewModel
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The slice of the player a launcher shortcut drives: start a book, open the player, set a sleep
 * timer, and know which book is current.
 *
 * Narrow on purpose — [handleShortcutAction] has to order "book is current" before "timer is set",
 * and that ordering is testable only against something smaller than a whole [PlayerViewModel].
 */
internal interface ShortcutPlayback {
    /** The book the player has loaded right now, or null when nothing is loaded. */
    val activeBookId: String?

    /** The loaded book over time, emitting its current value on collection. */
    val activeBookIds: Flow<String?>

    /** Starts [bookId] playing. */
    fun playBook(bookId: BookId)

    /** Opens the full player. */
    fun expand()

    /** Starts a sleep timer on the current book. */
    fun setSleepTimer(mode: SleepTimerMode)
}

/** Adapts this view model to the [ShortcutPlayback] a shortcut drives. */
internal fun PlayerViewModel.asShortcutPlayback(): ShortcutPlayback =
    object : ShortcutPlayback {
        override val activeBookId: String? get() = uiState.value.loadedBookIdOrNull()
        override val activeBookIds: Flow<String?> = uiState.map { it.loadedBookIdOrNull() }

        override fun playBook(bookId: BookId) = this@asShortcutPlayback.playBook(bookId)

        override fun expand() = this@asShortcutPlayback.expand()

        override fun setSleepTimer(mode: SleepTimerMode) = this@asShortcutPlayback.setSleepTimer(mode)
    }

private fun PlayerUiState.loadedBookIdOrNull(): String? = (this as? PlayerUiState.Loaded)?.run { book.id }
