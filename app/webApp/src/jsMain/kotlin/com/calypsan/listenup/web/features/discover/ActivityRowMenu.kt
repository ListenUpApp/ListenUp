package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.activityParts
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.buttonClasses

/** One thing an activity row's ⋯ menu offers (spec §3.5 row actions). */
internal sealed interface ActivityRowAction {
    /** Open the book the row is about. */
    data class OpenBook(
        val bookId: String,
    ) : ActivityRowAction

    /** Open the shelf the row is about. */
    data class OpenShelf(
        val shelfId: String,
    ) : ActivityRowAction

    /** Open the person who did it. */
    data class OpenProfile(
        val userId: String,
    ) : ActivityRowAction

    /** Put the row's book on one of your shelves. */
    data class AddToShelf(
        val bookId: String,
    ) : ActivityRowAction
}

/**
 * A row's actions in the board's order — a book row: the book, the person, add to a shelf; a shelf
 * row: the shelf and the person; a row about neither: nothing, since its link already opens the
 * person (plan Q5). The same rule as Android's and iOS's (plan C7).
 */
internal fun activityRowActions(row: ActivityUiModel): List<ActivityRowAction> {
    val bookId = row.bookId
    val shelfId = row.shelfId
    return when {
        bookId != null -> {
            listOf(
                ActivityRowAction.OpenBook(bookId),
                ActivityRowAction.OpenProfile(row.userId),
                ActivityRowAction.AddToShelf(bookId),
            )
        }

        shelfId != null -> {
            listOf(ActivityRowAction.OpenShelf(shelfId), ActivityRowAction.OpenProfile(row.userId))
        }

        else -> {
            emptyList()
        }
    }
}

/**
 * The row's visible ⋯ "More actions for {Person}'s activity" button and its menu (fixes a9): a real
 * button, so the keyboard reaches it and Enter opens it. Renders nothing for a row with no actions.
 *
 * @param onShelveOne Shelve the row's one book; null leaves "Add {book} to a shelf" out.
 */
@Composable
internal fun ActivityRowMenu(
    row: ActivityUiModel,
    onOpenBook: (String) -> Unit,
    onOpenShelf: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onShelveOne: ((String) -> Unit)?,
) {
    val subject = activityParts(row).highlight.orEmpty()
    val items =
        activityRowActions(row).mapNotNull { action ->
            when (action) {
                is ActivityRowAction.OpenBook -> {
                    MenuAction(DiscoverWords.openLabel(subject), WebIcon.Book, onSelect = { onOpenBook(action.bookId) })
                }

                is ActivityRowAction.OpenShelf -> {
                    MenuAction(
                        DiscoverWords.openLabel(subject),
                        WebIcon.Layers,
                        onSelect = { onOpenShelf(action.shelfId) },
                    )
                }

                is ActivityRowAction.OpenProfile -> {
                    MenuAction(
                        DiscoverWords.profileLabel(row.userDisplayName, row.isYou),
                        WebIcon.Person,
                        onSelect = { onOpenProfile(action.userId) },
                    )
                }

                is ActivityRowAction.AddToShelf -> {
                    onShelveOne?.let { shelve ->
                        MenuAction(
                            DiscoverWords.addToShelfLabel(subject),
                            WebIcon.Bookmark,
                            onSelect = { shelve(action.bookId) },
                        )
                    }
                }
            }
        }
    if (items.isEmpty()) return
    PopupMenu(
        items = items,
        label = DiscoverWords.moreActionsLabel(row.userDisplayName, row.isYou),
        icon = WebIcon.More,
        triggerClasses = buttonClasses(ButtonKind.Icon, ButtonSize.Lg),
    )
}
