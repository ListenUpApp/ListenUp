package com.calypsan.listenup.client.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a pending shortcut action that needs to be processed.
 *
 * Sealed hierarchy ensures all action types are handled exhaustively.
 */
sealed interface ShortcutAction {
    /**
     * Resume listening to the most recent book.
     */
    data object Resume : ShortcutAction

    /**
     * Play a specific book.
     *
     * @property bookId The ID of the book to play
     */
    data class PlayBook(
        val bookId: String,
    ) : ShortcutAction

    /**
     * Open library search.
     */
    data object Search : ShortcutAction

    /**
     * Show or set sleep timer.
     *
     * @property timerMinutes Optional preset duration in minutes
     */
    data class SleepTimer(
        val timerMinutes: Int? = null,
    ) : ShortcutAction

    /**
     * Navigate to a specific book's detail screen (from deep link).
     *
     * @property bookId The ID of the book to view
     */
    data class NavigateToBook(
        val bookId: String,
    ) : ShortcutAction

    /**
     * Navigate to a specific ABS import detail screen (from notification deep link).
     *
     * @property importId The ID of the import to view
     */
    data class NavigateToAbsImport(
        val importId: String,
    ) : ShortcutAction
}

/**
 * Manages pending shortcut action state for the application.
 *
 * When the app is opened via an app shortcut, the parsed action is stored
 * here and consumed by the navigation layer.
 *
 * Flow:
 * 1. User taps shortcut from launcher
 * 2. Android launches MainActivity with action intent
 * 3. MainActivity parses intent and calls setPendingAction()
 * 4. Navigation layer observes pendingAction and executes it
 * 5. After execution, consumeAction() clears the pending state
 *
 * This pattern ensures shortcuts work whether the app is:
 * - Not running (cold start)
 * - Already running (warm start via onNewIntent)
 * - In background (brought to foreground)
 *
 * Thread-safe via StateFlow - can be observed from any coroutine context.
 */
class ShortcutActionManager {
    /**
     * Observable flow of pending shortcut action.
     * Null when no action is pending.
     */
    val pendingAction: StateFlow<ShortcutAction?>
        field = MutableStateFlow<ShortcutAction?>(null)

    /**
     * Sets a pending action to be processed by navigation.
     *
     * Called when the app receives a shortcut intent.
     * Navigation layer will observe this and execute the action.
     *
     * @param action The shortcut action to execute
     */
    fun setPendingAction(action: ShortcutAction) {
        pendingAction.value = action
    }

    /**
     * Clears the pending action after it has been processed.
     *
     * Should be called after the action has been executed to prevent
     * re-execution on configuration changes.
     */
    fun consumeAction() {
        pendingAction.value = null
    }

    /**
     * Checks if there's a pending action without consuming it.
     */
    fun hasPendingAction(): Boolean = pendingAction.value != null
}
