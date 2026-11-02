package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Column
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
import com.calypsan.listenup.client.design.icons.ListenUpIcons
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
 * Book Detail's ⋮ menu, drawn from [groups] ([bookDetailMoreMenu]): Share; Progress; Edit; Admin; then
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
        BookDetailMenuItem.Share -> ListenUpIcons.Share
        BookDetailMenuItem.MarkFinished -> ListenUpIcons.CheckCircle
        BookDetailMenuItem.Restart -> ListenUpIcons.RestartAlt
        BookDetailMenuItem.MarkNotStarted -> ListenUpIcons.RadioButtonUnchecked
        BookDetailMenuItem.EditDetails -> ListenUpIcons.Edit
        BookDetailMenuItem.MatchDetails -> ListenUpIcons.Search
        BookDetailMenuItem.EditChapters -> ListenUpIcons.FormatListNumbered
        BookDetailMenuItem.AddToCollection -> ListenUpIcons.PlaylistAdd
        BookDetailMenuItem.DeleteFromServer -> ListenUpIcons.Delete
    }
