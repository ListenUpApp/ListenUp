package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.design.ActionsMenu
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.WebIcon

/**
 * What a reader can do about their own relationship to this book.
 *
 * ⛔ Until this existed, web's Book Detail was **read-only**: the page consumed `state` and offered
 * no action at all, so the twenty-odd finished functions on `BookDetailViewModel` had no entry
 * point. A reader could see that they were 40% through a book and had no way to say they had
 * finished it, abandoned it, or wanted to start again.
 *
 * Which items appear is the same rule both natives apply, and it is about what there is *to* do:
 * finishing is offered only when the book is not already finished, and clearing or restarting only
 * when there is something to clear.
 */
@Composable
fun BookActionsMenu(
    ready: BookDetailUiState.Ready,
    onMarkComplete: () -> Unit,
    onDiscardProgress: () -> Unit,
    onRestart: () -> Unit,
    onAddToShelf: () -> Unit,
    onAddToCollection: () -> Unit,
) {
    val items =
        progressActions(ready, onMarkComplete, onDiscardProgress, onRestart) +
            filingActions(ready, onAddToShelf, onAddToCollection)

    // ⛔ One flag for all three: they are the same round-trip through the same repository, and a
    // second request while one is in flight would race it to the same position record.
    val busy = ready.isMarkingComplete || ready.isDiscardingProgress || ready.isRestarting

    ActionsMenu(items = items, enabled = !busy)
}

/**
 * The three progress actions, filtered to the ones that would do something.
 *
 * ⛔ Both "clear" actions key on `progress != null || isComplete`, not on progress alone: a finished
 * book has no in-progress position, so keying on progress would leave a reader who marked a book
 * finished by mistake with no way to undo it.
 */
internal fun progressActions(
    ready: BookDetailUiState.Ready,
    onMarkComplete: () -> Unit,
    onDiscardProgress: () -> Unit,
    onRestart: () -> Unit,
): List<MenuAction> {
    val hasSomethingToClear = ready.progress != null || ready.isComplete
    return buildList {
        if (!ready.isComplete) {
            add(MenuAction("Mark as finished", WebIcon.Check, onMarkComplete))
        }
        if (hasSomethingToClear) {
            add(MenuAction("Mark as not started", WebIcon.Minus, onDiscardProgress))
            add(MenuAction("Restart book", WebIcon.ArrowUp, onRestart))
        }
    }
}

/**
 * Where this book can be filed.
 *
 * ⛔ Collections are admin-managed, and the entry is gated on `isAdmin` here as well as being
 * refused server-side. Android gates its own picker the same way and says why: defence in depth, so
 * the picker cannot render for a non-admin even if the flag behind it leaks true.
 *
 * "Add to shelf" has no such gate — a shelf is the listener's own.
 */
internal fun filingActions(
    ready: BookDetailUiState.Ready,
    onAddToShelf: () -> Unit,
    onAddToCollection: () -> Unit,
): List<MenuAction> =
    buildList {
        add(MenuAction("Add to shelf", WebIcon.Bookmark, onAddToShelf))
        if (ready.isAdmin) {
            add(MenuAction("Add to collection", WebIcon.Layers, onAddToCollection))
        }
    }

private const val ICON_SIZE = 18

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"
