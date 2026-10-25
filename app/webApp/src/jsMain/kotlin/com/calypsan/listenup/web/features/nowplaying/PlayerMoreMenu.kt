package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerMenus
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.buttonClasses
import com.calypsan.listenup.web.features.bookdetail.documentUrl
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement

/**
 * Now Playing's "More ▾" (§5.5.7), from the shared [PlayerMenus] so the three platforms list the same rows in the same
 * order. Web lists Go to series at every width. Open PDF is a real link into a new tab, so no popup blocker stops it.
 * Close book sits apart, in the destructive colour. The trigger is named for the book (WCAG 2.4.6).
 */
@Composable
internal fun PlayerMoreMenu(
    state: PlayerUiState.Loaded,
    /** A chosen row, with the More trigger, which a panel the row opens hangs from (Volume boost, C9). */
    onItem: (PlayerMenuItem, opener: HTMLElement?) -> Unit,
) {
    val anchor = remember { MoreAnchor() }
    val bookId = state.book.id
    val items =
        PlayerMenus.moreMenu(state, includesSeries = true, includesShortcuts = false).flatMap { group ->
            group.items.map { item ->
                MenuAction(
                    label = item.label(),
                    icon = null,
                    onSelect = { onItem(item, anchor.trigger()) },
                    group = if (group.isDestructive) CLOSE_GROUP else null,
                    destructive = group.isDestructive,
                    href = (item as? PlayerMenuItem.OpenPdf)?.let { documentUrl(bookId, it.documentId) },
                    opensNewTab = item is PlayerMenuItem.OpenPdf,
                )
            }
        }
    Div(attrs = {
        classes("np-more")
        ref { element ->
            anchor.element = element
            onDispose { anchor.element = null }
        }
    }) {
        PopupMenu(
            items = items,
            label = NowPlayingWords.moreFor(state.book.title),
            icon = WebIcon.ChevronDown,
            triggerClasses = buttonClasses(ButtonKind.Secondary, ButtonSize.Md),
            triggerText = MORE,
            trailingIcon = true,
        )
    }
}

/** The `.np-more` wrapper, captured by `ref`, whose menu trigger a panel opened from More hangs from. */
private class MoreAnchor {
    var element: HTMLElement? = null

    fun trigger(): HTMLElement? = element?.querySelector("button[aria-haspopup=menu]") as? HTMLElement
}

private fun PlayerMenuItem.label(): String =
    when (this) {
        PlayerMenuItem.GoToBook -> "Go to book"
        is PlayerMenuItem.GoToSeries -> "Go to series"
        is PlayerMenuItem.OpenPdf -> "Open PDF ↗"
        is PlayerMenuItem.VolumeBoost -> NowPlayingWords.boostRow(boostDb)
        PlayerMenuItem.KeyboardShortcuts -> "Keyboard shortcuts"
        PlayerMenuItem.CloseBook -> "Close book"
    }

private const val MORE = "More"

/** The destructive group's accessible name: it draws no heading, its divider and colour say it is apart. */
private const val CLOSE_GROUP = "Close book"
