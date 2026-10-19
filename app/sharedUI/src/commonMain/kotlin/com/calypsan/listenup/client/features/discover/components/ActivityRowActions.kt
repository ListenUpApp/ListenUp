package com.calypsan.listenup.client.features.discover.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.activityParts
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_activity_add_book_to_shelf
import listenup.composeapp.generated.resources.discover_activity_open
import listenup.composeapp.generated.resources.discover_activity_profile_a11y
import listenup.composeapp.generated.resources.discover_activity_your_profile_a11y
import org.jetbrains.compose.resources.stringResource

/** One thing an activity row can be asked to do beyond its tap (spec §3.5 row actions). */
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
 * The actions a row offers, in the board's order. A book row: the book, the person, add to a shelf.
 * A shelf row: the shelf and the person. A row about neither (a join, a milestone) offers nothing,
 * because its tap already opens the person (plan Q5).
 *
 * The menu, TalkBack's custom actions and the Menu key all read this one list (plan C7).
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

/** What [action] is called on [row]: "Open Piranesi", "Maya Chen's profile", "Add Piranesi to a shelf". */
@Composable
internal fun ActivityRowAction.label(row: ActivityUiModel): String {
    val subject = activityParts(row).highlight.orEmpty()
    return when (this) {
        is ActivityRowAction.OpenBook, is ActivityRowAction.OpenShelf -> {
            stringResource(Res.string.discover_activity_open, subject)
        }

        is ActivityRowAction.OpenProfile -> {
            if (row.isYou) {
                stringResource(Res.string.discover_activity_your_profile_a11y)
            } else {
                stringResource(Res.string.discover_activity_profile_a11y, row.userDisplayName)
            }
        }

        is ActivityRowAction.AddToShelf -> {
            stringResource(Res.string.discover_activity_add_book_to_shelf, subject)
        }
    }
}

/** The menu item's leading icon. */
internal val ActivityRowAction.icon: ImageVector
    get() =
        when (this) {
            is ActivityRowAction.OpenBook -> Icons.AutoMirrored.Filled.MenuBook
            is ActivityRowAction.OpenShelf -> Icons.AutoMirrored.Filled.LibraryBooks
            is ActivityRowAction.OpenProfile -> Icons.Default.Person
            is ActivityRowAction.AddToShelf -> Icons.AutoMirrored.Filled.PlaylistAdd
        }
