package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.library.BookMenuPrimary
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_listen_again
import listenup.composeapp.generated.resources.book_detail_resume
import listenup.composeapp.generated.resources.common_play
import listenup.composeapp.generated.resources.common_select
import listenup.composeapp.generated.resources.library_menu_add_to_collection
import listenup.composeapp.generated.resources.library_menu_add_to_shelf
import listenup.composeapp.generated.resources.library_menu_admin
import listenup.composeapp.generated.resources.library_menu_edit_details
import listenup.composeapp.generated.resources.library_menu_primary_with_time
import listenup.composeapp.generated.resources.library_menu_share
import org.jetbrains.compose.resources.stringResource

/**
 * What a Library book's menu can do. A null action hides its item, never disables it (the [SelectionToolbar] rule):
 * Share without a server link, Add to shelf while a selection is under way, and the admin pair for a member.
 */
internal data class LibraryBookMenuActions(
    val onPrimary: () -> Unit,
    val onAddToShelf: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onSelect: () -> Unit,
    val onAddToCollection: (() -> Unit)?,
    val onEditDetails: (() -> Unit)?,
)

/** How the Books grid builds a book's menu: what it leads with, and what each item does, by book id. */
internal data class LibraryBookMenuHost(
    val primaryFor: (String) -> BookMenuPrimary,
    val actionsFor: (String) -> LibraryBookMenuActions,
)

/**
 * A Library book's menu (spec §3.1.2; Simon's M3E call): one M3 menu hung from the cover a long press or right-click
 * landed on (m3.material.io/components/menus), with no lifted preview. The groups, in the board's order: the primary
 * (Play, Resume with its time left, or Listen again) in the Library coral; Add to shelf… and Share…; Select; then, for
 * an admin, an "Admin" group of Add to collection… and Edit details. Each item closes the menu after it acts.
 */
@Composable
internal fun LibraryBookMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    primary: BookMenuPrimary,
    actions: LibraryBookMenuActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem(
            label = primaryLabel(primary),
            icon = Icons.Filled.PlayArrow,
            emphasised = true,
            onDismiss = onDismiss,
            onClick = actions.onPrimary,
        )
        val hasMiddle = actions.onAddToShelf != null || actions.onShare != null
        if (hasMiddle) HorizontalDivider()
        actions.onAddToShelf?.let { action ->
            MenuItem(
                label = stringResource(Res.string.library_menu_add_to_shelf),
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                onDismiss = onDismiss,
                onClick = action,
            )
        }
        actions.onShare?.let { action ->
            MenuItem(
                label = stringResource(Res.string.library_menu_share),
                icon = Icons.Filled.Share,
                onDismiss = onDismiss,
                onClick = action,
            )
        }
        HorizontalDivider()
        MenuItem(
            label = stringResource(Res.string.common_select),
            icon = Icons.Outlined.CheckCircle,
            onDismiss = onDismiss,
            onClick = actions.onSelect,
        )
        if (actions.onAddToCollection != null || actions.onEditDetails != null) {
            HorizontalDivider()
            Text(
                text = stringResource(Res.string.library_menu_admin),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(MenuDefaults.DropdownMenuItemContentPadding)
                        .padding(vertical = Spacing.xs)
                        .semantics { heading() },
            )
            actions.onAddToCollection?.let { action ->
                MenuItem(
                    label = stringResource(Res.string.library_menu_add_to_collection),
                    icon = Icons.Outlined.CollectionsBookmark,
                    onDismiss = onDismiss,
                    onClick = action,
                )
            }
            actions.onEditDetails?.let { action ->
                MenuItem(
                    label = stringResource(Res.string.library_menu_edit_details),
                    icon = Icons.Filled.Edit,
                    onDismiss = onDismiss,
                    onClick = action,
                )
            }
        }
    }
}

/** "Play", "Resume · 6h 2m" or "Listen again". The time is the card's own spelling of it. */
@Composable
private fun primaryLabel(primary: BookMenuPrimary): String =
    when (primary) {
        BookMenuPrimary.Play -> {
            stringResource(Res.string.common_play)
        }

        BookMenuPrimary.ListenAgain -> {
            stringResource(Res.string.book_detail_listen_again)
        }

        is BookMenuPrimary.Resume -> {
            stringResource(
                Res.string.library_menu_primary_with_time,
                stringResource(Res.string.book_detail_resume),
                DurationFormatter.hoursMinutes(primary.timeLeftMs.milliseconds),
            )
        }
    }

@Composable
private fun MenuItem(
    label: String,
    icon: ImageVector,
    emphasised: Boolean = false,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    // The primary carries the Library's action colour (N6); the rest stay on the menu's own roles.
    val color = if (emphasised) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = {
            haptics.press()
            onClick()
            onDismiss()
        },
    )
}

/** One menu row, and the space a divider and the menu's own padding take. */
private val MenuRowHeight = 48.dp
private val MenuDividerHeight = 17.dp
private val MenuChromeHeight = 16.dp

/**
 * How tall [actions]' menu will be, for making room below the cover before it opens: its rows, the dividers between
 * groups, and the Admin label.
 */
internal fun estimatedMenuHeight(actions: LibraryBookMenuActions): Dp {
    val middle = listOfNotNull(actions.onAddToShelf, actions.onShare).size
    val admin = listOfNotNull(actions.onAddToCollection, actions.onEditDetails).size
    val rows = 2 + middle + admin + if (admin > 0) 1 else 0
    val dividers = 1 + (if (middle > 0) 1 else 0) + (if (admin > 0) 1 else 0)
    return MenuRowHeight * rows + MenuDividerHeight * dividers + MenuChromeHeight
}
