package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

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
) {
    val items = progressActions(ready, onMarkComplete, onDiscardProgress, onRestart)
    // No menu at all rather than an empty one: a button that opens nothing is worse than no button.
    if (items.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    // ⛔ One flag for all three: they are the same round-trip through the same repository, and a
    // second request while one is in flight would race it to the same position record.
    val busy = ready.isMarkingComplete || ready.isDiscardingProgress || ready.isRestarting

    Div(attrs = { classes("menu-anchor") }) {
        Button(attrs = {
            classes("btn-sq")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr("aria-label", "More actions")
            attr("aria-expanded", open.toString())
            if (busy) attr("disabled", "")
            onClick { open = !open }
        }) { Icon(WebIcon.Grip, size = ICON_SIZE) }

        if (open) {
            Div(attrs = {
                classes("menu")
                attr("role", "menu")
            }) {
                items.forEach { item ->
                    // ⛔ A real <button role="menuitem">, not the clickable <div> the account menu
                    // uses: a div is unreachable by keyboard and announces nothing. Same CSS, so it
                    // looks identical; the account menu wants the same treatment separately.
                    Button(attrs = {
                        classes("menu-i")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        attr("role", "menuitem")
                        onClick {
                            open = false
                            item.onSelect()
                        }
                    }) {
                        Icon(item.icon, size = ICON_SIZE)
                        Text(item.label)
                    }
                }
            }
        }
    }
}

/** One entry in the menu. */
internal data class BookAction(
    val label: String,
    val icon: WebIcon,
    val onSelect: () -> Unit,
)

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
): List<BookAction> {
    val hasSomethingToClear = ready.progress != null || ready.isComplete
    return buildList {
        if (!ready.isComplete) {
            add(BookAction("Mark as finished", WebIcon.Check, onMarkComplete))
        }
        if (hasSomethingToClear) {
            add(BookAction("Mark as not started", WebIcon.Minus, onDiscardProgress))
            add(BookAction("Restart book", WebIcon.ArrowUp, onRestart))
        }
    }
}

private const val ICON_SIZE = 18

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"
