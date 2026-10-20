package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuHeading
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuItem
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailMoreMenu
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.buttonClasses

/**
 * Book Detail's More menu (spec §2.7): a "More ▾" text button after Resume and Add to shelf, opening the shared rule's
 * groups ([bookDetailMoreMenu]) — Share…; Progress; Edit; Admin; then Delete from server… alone, in the danger colour,
 * with no heading. The same rule Android and iOS draw, so the three cannot disagree about who gets what.
 *
 * [onItem] runs every item but the two that erase a listening position: Restart and Mark as not started ask first
 * here, in the natives' words, and only a confirm calls [onRestart] or [onDiscardProgress]. While a progress write is
 * in flight the three progress items wait; the rest stay live. A held book has no menu at all.
 *
 * The trigger's accessible name "More actions" contains its visible word "More" (WCAG 2.5.3, Label in Name).
 */
@Composable
fun BookActionsMenu(
    ready: BookDetailUiState.Ready,
    onItem: (BookDetailMenuItem) -> Unit,
    onDiscardProgress: () -> Unit,
    onRestart: () -> Unit,
) {
    // ⛔ The two actions that erase a listening position ask first, as both natives do. Web fired
    // them on the click, so one stray press on a menu item threw away where a reader was in a
    // twenty-hour book with no way back.
    var pending by remember { mutableStateOf<ProgressConfirmation?>(null) }

    val items =
        bookDetailMoreMenu(ready).flatMap { group ->
            group.items.map { item ->
                MenuAction(
                    label = item.webLabel(),
                    icon = item.webIcon(),
                    onSelect = {
                        when (item) {
                            BookDetailMenuItem.MarkNotStarted -> pending = ProgressConfirmation.MarkNotStarted
                            BookDetailMenuItem.Restart -> pending = ProgressConfirmation.Restart
                            else -> onItem(item)
                        }
                    },
                    group = group.heading?.webLabel(),
                    destructive = group.isDestructive,
                    enabled = !(ready.isChangingProgress && item.isProgress()),
                )
            }
        }
    if (items.isNotEmpty()) {
        PopupMenu(
            items = items,
            label = "More actions",
            icon = WebIcon.ChevronDown,
            triggerClasses = buttonClasses(ButtonKind.Secondary, ButtonSize.Lg) + MORE_TRIGGER_CLASS,
            triggerText = "More",
            trailingIcon = true,
        )
    }

    pending?.let { confirmation ->
        ConfirmDialog(
            open = true,
            title = confirmation.verb,
            body = confirmation.prompt,
            confirmLabel = confirmation.verb,
            onConfirm = {
                pending = null
                when (confirmation) {
                    ProgressConfirmation.MarkNotStarted -> onDiscardProgress()
                    ProgressConfirmation.Restart -> onRestart()
                }
            },
            onDismiss = { pending = null },
        )
    }
}

/** The More trigger's own class: `.bd-more` is already the synopsis's "Read more". */
internal const val MORE_TRIGGER_CLASS = "bd-menu"

/**
 * The two progress actions that cannot be undone, and what each asks — the natives' strings
 * (`book_detail_mark_not_started_prompt`, `book_detail_restart_prompt`) character for character.
 */
private enum class ProgressConfirmation(
    val verb: String,
    val prompt: String,
) {
    MarkNotStarted("Mark as not started", "Clear your progress and mark this as not started?"),
    Restart(
        "Restart book",
        "Start over from the beginning? This resets your position to the start of the book.",
    ),
}

/** The item's words on the web; an ellipsis where choosing it asks or opens something first. */
internal fun BookDetailMenuItem.webLabel(): String =
    when (this) {
        BookDetailMenuItem.Share -> "Share…"
        BookDetailMenuItem.MarkFinished -> "Mark as finished…"
        BookDetailMenuItem.Restart -> "Restart from the beginning…"
        BookDetailMenuItem.MarkNotStarted -> "Mark as not started…"
        BookDetailMenuItem.EditDetails -> "Edit details"
        BookDetailMenuItem.MatchDetails -> "Match details…"
        BookDetailMenuItem.EditChapters -> "Edit chapters"
        BookDetailMenuItem.AddToCollection -> "Add to collection…"
        BookDetailMenuItem.DeleteFromServer -> "Delete from server…"
    }

private fun BookDetailMenuItem.webIcon(): WebIcon =
    when (this) {
        BookDetailMenuItem.Share -> WebIcon.Share
        BookDetailMenuItem.MarkFinished -> WebIcon.Check
        BookDetailMenuItem.Restart -> WebIcon.ArrowUp
        BookDetailMenuItem.MarkNotStarted -> WebIcon.Minus
        BookDetailMenuItem.EditDetails -> WebIcon.Pencil
        BookDetailMenuItem.MatchDetails -> WebIcon.Sparkles
        BookDetailMenuItem.EditChapters -> WebIcon.ListOrdered
        BookDetailMenuItem.AddToCollection -> WebIcon.Layers
        BookDetailMenuItem.DeleteFromServer -> WebIcon.Trash
    }

private fun BookDetailMenuHeading.webLabel(): String =
    when (this) {
        BookDetailMenuHeading.Progress -> "Progress"
        BookDetailMenuHeading.Edit -> "Edit"
        BookDetailMenuHeading.Admin -> "Admin"
    }

private fun BookDetailMenuItem.isProgress(): Boolean =
    this == BookDetailMenuItem.MarkFinished || this == BookDetailMenuItem.Restart ||
        this == BookDetailMenuItem.MarkNotStarted
