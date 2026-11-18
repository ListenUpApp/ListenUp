package com.calypsan.listenup.client.features.discover.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.presentation.discover.ActivityParts
import com.calypsan.listenup.client.presentation.discover.ActivityRowAction
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_activity_add_book_to_shelf
import listenup.composeapp.generated.resources.discover_activity_open
import listenup.composeapp.generated.resources.discover_activity_profile_a11y
import listenup.composeapp.generated.resources.discover_activity_your_profile_a11y
import org.jetbrains.compose.resources.stringResource

/**
 * What this action is called on a row reading [parts], by [displayName] (or by the viewer, when [isYou]):
 * "Open Piranesi", "Maya Chen's profile", "Add Piranesi to a shelf".
 */
@Composable
internal fun ActivityRowAction.label(
    parts: ActivityParts,
    isYou: Boolean,
    displayName: String,
): String {
    val subject = parts.highlight.orEmpty()
    return when (this) {
        is ActivityRowAction.OpenBook, is ActivityRowAction.OpenShelf -> {
            stringResource(Res.string.discover_activity_open, subject)
        }

        is ActivityRowAction.OpenProfile -> {
            if (isYou) {
                stringResource(Res.string.discover_activity_your_profile_a11y)
            } else {
                stringResource(Res.string.discover_activity_profile_a11y, displayName)
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
