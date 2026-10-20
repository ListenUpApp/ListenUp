package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.platform.testTag
import com.calypsan.listenup.client.design.components.MenuGroupLabel
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuGroup
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuHeading
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuItem
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailMoreMenu
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_menu_delete
import listenup.composeapp.generated.resources.book_detail_menu_edit
import listenup.composeapp.generated.resources.book_detail_menu_edit_chapters
import listenup.composeapp.generated.resources.book_detail_menu_mark_finished
import listenup.composeapp.generated.resources.book_detail_menu_mark_not_started
import listenup.composeapp.generated.resources.book_detail_menu_match_details
import listenup.composeapp.generated.resources.book_detail_menu_progress
import listenup.composeapp.generated.resources.book_detail_menu_restart
import listenup.composeapp.generated.resources.library_menu_add_to_collection
import listenup.composeapp.generated.resources.library_menu_admin
import listenup.composeapp.generated.resources.library_menu_edit_details
import listenup.composeapp.generated.resources.library_menu_share
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Book Detail's ⋮ menu (spec §2.7), drawn from [groups] ([bookDetailMoreMenu]): Share; Progress; Edit; Admin; then
 * Delete from server… alone, in the error colour. `DropdownMenu` has no native sections, so each group after the first
 * opens with a `HorizontalDivider`, and a named group with a [MenuGroupLabel] (m3.material.io/components/menus). While
 * [progressBusy], the three progress items wait. Choosing an item closes the menu first.
 */
@Composable
fun BookActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    groups: List<BookDetailMenuGroup>,
    progressBusy: Boolean,
    onItem: (BookDetailMenuItem) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        groups.forEachIndexed { index, group ->
            if (index > 0) HorizontalDivider()
            Column(Modifier.testTag("bookMenuGroup:${group.key()}")) {
                group.heading?.let { MenuGroupLabel(stringResource(it.label())) }
                group.items.forEach { item ->
                    BookMenuItem(
                        item = item,
                        destructive = group.isDestructive,
                        enabled = !(progressBusy && item.isProgress()),
                        onClick = {
                            onDismiss()
                            onItem(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookMenuItem(
    item: BookDetailMenuItem,
    destructive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(stringResource(item.label())) },
        leadingIcon = { Icon(item.icon(), contentDescription = null) },
        colors = MenuDefaults.itemColors(textColor = tint, leadingIconColor = tint),
        enabled = enabled,
        onClick = {
            haptics.press()
            onClick()
        },
    )
}

private fun BookDetailMenuGroup.key(): String =
    when {
        isDestructive -> "delete"
        heading == BookDetailMenuHeading.Progress -> "progress"
        heading == BookDetailMenuHeading.Edit -> "edit"
        heading == BookDetailMenuHeading.Admin -> "admin"
        else -> "share"
    }

private fun BookDetailMenuItem.isProgress(): Boolean =
    this == BookDetailMenuItem.MarkFinished || this == BookDetailMenuItem.Restart ||
        this == BookDetailMenuItem.MarkNotStarted

private fun BookDetailMenuHeading.label(): StringResource =
    when (this) {
        BookDetailMenuHeading.Progress -> Res.string.book_detail_menu_progress
        BookDetailMenuHeading.Edit -> Res.string.book_detail_menu_edit
        BookDetailMenuHeading.Admin -> Res.string.library_menu_admin
    }

private fun BookDetailMenuItem.label(): StringResource =
    when (this) {
        BookDetailMenuItem.Share -> Res.string.library_menu_share
        BookDetailMenuItem.MarkFinished -> Res.string.book_detail_menu_mark_finished
        BookDetailMenuItem.Restart -> Res.string.book_detail_menu_restart
        BookDetailMenuItem.MarkNotStarted -> Res.string.book_detail_menu_mark_not_started
        BookDetailMenuItem.EditDetails -> Res.string.library_menu_edit_details
        BookDetailMenuItem.MatchDetails -> Res.string.book_detail_menu_match_details
        BookDetailMenuItem.EditChapters -> Res.string.book_detail_menu_edit_chapters
        BookDetailMenuItem.AddToCollection -> Res.string.library_menu_add_to_collection
        BookDetailMenuItem.DeleteFromServer -> Res.string.book_detail_menu_delete
    }

private fun BookDetailMenuItem.icon(): ImageVector =
    when (this) {
        BookDetailMenuItem.Share -> Icons.Outlined.Share
        BookDetailMenuItem.MarkFinished -> Icons.Outlined.CheckCircle
        BookDetailMenuItem.Restart -> Icons.Outlined.RestartAlt
        BookDetailMenuItem.MarkNotStarted -> Icons.Outlined.RadioButtonUnchecked
        BookDetailMenuItem.EditDetails -> Icons.Outlined.Edit
        BookDetailMenuItem.MatchDetails -> Icons.Outlined.Search
        BookDetailMenuItem.EditChapters -> Icons.Outlined.FormatListNumbered
        BookDetailMenuItem.AddToCollection -> Icons.AutoMirrored.Outlined.PlaylistAdd
        BookDetailMenuItem.DeleteFromServer -> Icons.Outlined.Delete
    }
