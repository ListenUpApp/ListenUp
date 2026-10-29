package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import org.w3c.dom.events.Event
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
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
import org.w3c.dom.MediaQueryList
import kotlinx.browser.window

/**
 * Now Playing's "More ▾" (§5.5.7), from the shared [PlayerMenus] so the three platforms list the same rows in the same
 * order. Below 760px it lists Go to series and Go to author, which the wider page shows as links instead; then Keyboard
 * shortcuts (with its key, ?) before Close book. Open PDF is a real link into a new tab, so no popup blocker stops it.
 * Close book sits apart, in the destructive colour. The trigger is named for the book (WCAG 2.4.6).
 */
@Composable
internal fun PlayerMoreMenu(
    state: PlayerUiState.Loaded,
    /** Below 760px: the page shows no series line and no author byline, so More carries Go to series and Go to author. */
    compact: Boolean,
    /** A chosen row, with the More trigger, which a panel the row opens hangs from (Volume boost, C9). */
    onItem: (PlayerMenuItem, opener: HTMLElement?) -> Unit,
) {
    val anchor = remember { MoreAnchor() }
    val bookId = state.book.id
    val items =
        PlayerMenus
            .moreMenu(
                state,
                includesSeries = compact,
                includesAuthor = compact,
                includesShortcuts = true,
            ).flatMap { group ->
                group.items.map { item ->
                    MenuAction(
                        label = item.label(),
                        icon = null,
                        onSelect = { onItem(item, anchor.trigger()) },
                        group = if (group.isDestructive) CLOSE_GROUP else null,
                        destructive = group.isDestructive,
                        href = (item as? PlayerMenuItem.OpenPdf)?.let { documentUrl(bookId, it.documentId) },
                        opensNewTab = item is PlayerMenuItem.OpenPdf,
                        keyShortcut = PlayerKeyAria.SHORTCUTS.takeIf { item == PlayerMenuItem.KeyboardShortcuts },
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

/**
 * Whether [element]'s own window is at the page's phone width (below 760px), now and whenever its media query flips.
 * Asked of the element's window, not the app's, as [panelPlacementFor] is: it answers to the viewport the page is in.
 */
@Composable
internal fun rememberIsCompactAt(element: HTMLElement?): Boolean {
    var compact by remember { mutableStateOf(false) }
    DisposableEffect(element) {
        var query: MediaQueryList? = null
        val listener: (Event) -> Unit = { compact = query?.matches ?: false }
        // Asked once the insertion has landed: a node built in one window and inserted into a frame's document only
        // belongs to the frame's window (and answers to its width) after the frame adopts it.
        val timer =
            window.setTimeout({
                query = element?.ownerDocument?.defaultView?.matchMedia(COMPACT_QUERY)
                compact = query?.matches ?: false
                query?.addEventListener("change", listener)
            }, 0)
        onDispose {
            window.clearTimeout(timer)
            query?.removeEventListener("change", listener)
        }
    }
    return compact
}

private const val COMPACT_QUERY = "(max-width: 759px)"

/** The `.np-more` wrapper, captured by `ref`, whose menu trigger a panel opened from More hangs from. */
private class MoreAnchor {
    var element: HTMLElement? = null

    fun trigger(): HTMLElement? = element?.querySelector("button[aria-haspopup=menu]") as? HTMLElement
}

private fun PlayerMenuItem.label(): String =
    when (this) {
        PlayerMenuItem.GoToBook -> "Go to book"
        is PlayerMenuItem.GoToSeries -> "Go to series"
        is PlayerMenuItem.GoToAuthor -> NowPlayingWords.GO_TO_AUTHOR
        PlayerMenuItem.ChooseAuthor -> "${NowPlayingWords.GO_TO_AUTHOR}…"
        is PlayerMenuItem.OpenPdf -> "Open PDF ↗"
        is PlayerMenuItem.VolumeBoost -> NowPlayingWords.boostRow(boostDb)
        PlayerMenuItem.KeyboardShortcuts -> "Keyboard shortcuts"
        PlayerMenuItem.CloseBook -> "Close book"
    }

private const val MORE = "More"

/** The destructive group's accessible name: it draws no heading, its divider and colour say it is apart. */
private const val CLOSE_GROUP = "Close book"
