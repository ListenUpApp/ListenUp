package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.discover.ActivityRowAction
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.activityParts
import com.calypsan.listenup.client.presentation.discover.activityRowActions
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.buttonClasses

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
        activityRowActions(row, canShelve = onShelveOne != null).mapNotNull { action ->
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
