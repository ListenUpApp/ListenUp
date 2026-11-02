package com.calypsan.listenup.client.features.discover.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.presentation.discover.ActivityRowAction
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.activityParts
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_activity_add_book_to_shelf
import listenup.composeapp.generated.resources.discover_activity_open
import listenup.composeapp.generated.resources.discover_activity_profile_a11y
import listenup.composeapp.generated.resources.discover_activity_your_profile_a11y
import org.jetbrains.compose.resources.stringResource

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
            is ActivityRowAction.OpenBook -> ListenUpIcons.MenuBookFilled
            is ActivityRowAction.OpenShelf -> ListenUpIcons.LibraryBooksFilled
            is ActivityRowAction.OpenProfile -> ListenUpIcons.PersonFilled
            is ActivityRowAction.AddToShelf -> ListenUpIcons.PlaylistAddFilled
        }
