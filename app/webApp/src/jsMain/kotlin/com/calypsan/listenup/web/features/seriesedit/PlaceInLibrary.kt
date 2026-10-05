package com.calypsan.listenup.web.features.seriesedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.seriesdetail.seriesBookCount
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLElement

/**
 * "Place in library" — where this series sits (Part of + Move into…) and the series inside it
 * (reorder + Add sub-series).
 *
 * ⛔ **None of this is part of Save.** The server owns the hierarchy, so every control here acts the
 * moment it is chosen and the result comes back through sync — the caption says so, because a
 * reader who then presses Cancel deserves to know it will not undo a move. For the same reason the
 * controls are disabled offline, with a banner saying why, while the rest of the form still saves.
 *
 * Reorder works three ways: a drag by the handle, and visible Move earlier / Move later buttons on
 * every row so the list works by keyboard alone. Every route sends ONE `ChildSeriesReordered` with
 * the whole new order. Nothing is written ahead of the server, so focus follows the moved row when
 * the new order arrives, and a polite live region says where it went (en.json's `series.moved_a11y`).
 */
@Composable
internal fun PlaceInLibrarySection(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onAddSubSeriesEvent: (AddSubSeriesEvent) -> Unit,
) {
    val online = state.isOnline
    if (!online) OfflineBanner()

    Div(attrs = { classes("sh-partof") }) {
        Div(attrs = { classes("sh-partof-text") }) {
            // en.json's series.part_of
            Span(attrs = { classes("sh-partof-l") }) { Text("Part of") }
            // en.json's series.top_level
            Span(attrs = { classes("sh-partof-v") }) { Text(state.parentName ?: "Top level") }
        }
        if (state.hierarchyBusy) {
            Span(attrs = {
                classes("sh-busy")
                attr("role", "status")
            }) { Span(attrs = { classes("sr-only") }) { Text("Saving…") } }
        }
        Button(
            kind = ButtonKind.Secondary,
            size = ButtonSize.Sm,
            enabled = online,
            pressable = !state.hierarchyBusy,
            onClick = { onEvent(SeriesEditUiEvent.ParentPickerOpened) },
        ) {
            // en.json's series.move_into
            Text("Move into…")
        }
    }

    SubSeriesList(state, onEvent)

    Div(attrs = { classes("sh-add-row") }) {
        Button(
            kind = ButtonKind.Secondary,
            size = ButtonSize.Sm,
            enabled = online,
            onClick = { onAddSubSeriesEvent(AddSubSeriesEvent.Opened) },
        ) {
            Icon(WebIcon.Plus, size = ICON)
            // en.json's series.add_subseries
            Text("Add sub-series")
        }
    }
    // en.json's series.hierarchy_caption
    P(attrs = { classes("sh-caption") }) {
        Text("Saved as soon as you choose. Needs a connection to the server.")
    }
}

@Composable
private fun OfflineBanner() {
    Div(attrs = {
        classes("sh-offline")
        attr("role", "status")
    }) {
        Icon(WebIcon.Alert, size = ICON)
        Div {
            // en.json's series.offline_title
            P(attrs = { classes("sh-offline-t") }) { Text("You're offline") }
            // en.json's series.offline_body
            P(attrs = { classes("sh-offline-b") }) {
                Text("Moving series and reordering sub-series need the server. Everything else here still saves.")
            }
        }
    }
}

/** Which way a row moved, so focus can return to the same button on it once the new order lands. */
private enum class MoveDirection { Earlier, Later }

@Composable
private fun SubSeriesList(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    val children = state.childSeries
    val canReorder = state.isOnline
    var announcement by remember { mutableStateOf("") }
    var focusAfterMove by remember { mutableStateOf<Pair<String, MoveDirection>?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }

    fun reorder(
        movedId: String,
        toIndex: Int,
        direction: MoveDirection?,
    ) {
        val from = children.indexOfFirst { it.id.value == movedId }
        if (from < 0 || toIndex !in children.indices || from == toIndex) return
        val reordered = children.toMutableList().apply { add(toIndex, removeAt(from)) }
        onEvent(SeriesEditUiEvent.ChildSeriesReordered(reordered.map { it.id.value }))
        // en.json's series.moved_a11y
        announcement = "${children[from].displayName} moved to position ${toIndex + 1} of ${children.size}"
        focusAfterMove = direction?.let { movedId to it }
    }

    // Focus follows the moved row once the server's new order arrives through sync.
    LaunchedEffect(children) {
        val (id, direction) = focusAfterMove ?: return@LaunchedEffect
        val row = document.querySelector("[data-sub-id=\"$id\"]") as? HTMLElement ?: return@LaunchedEffect
        val preferred = if (direction == MoveDirection.Earlier) ".sh-earlier" else ".sh-later"
        val fallback = if (direction == MoveDirection.Earlier) ".sh-later" else ".sh-earlier"
        val target =
            (
                row.querySelector(
                    "$preferred:not([disabled])",
                ) ?: row.querySelector("$fallback:not([disabled])")
            ) as? HTMLElement
        target?.focus()
        focusAfterMove = null
    }

    Div(attrs = { classes("sh-subs-head") }) {
        // en.json's series.subseries
        H3(attrs = { classes("sh-subs-t") }) { Text("Sub-series") }
        Span(attrs = { classes("sd-count-badge") }) { Text(children.size.toString()) }
    }
    Div(attrs = {
        classes("sr-only")
        attr("aria-live", "polite")
    }) { Text(announcement) }
    if (children.isEmpty()) return

    Ol(attrs = { classes("sh-subs") }) {
        children.forEachIndexed { index, child ->
            key(child.id.value) {
                SubSeriesRow(
                    child = child,
                    canReorder = canReorder,
                    isFirst = index == 0,
                    isLast = index == children.lastIndex,
                    isDragging = draggingId == child.id.value,
                    onEarlier = { reorder(child.id.value, index - 1, MoveDirection.Earlier) },
                    onLater = { reorder(child.id.value, index + 1, MoveDirection.Later) },
                    onDragStart = { draggingId = child.id.value },
                    onDragEnd = { draggingId = null },
                    onDrop = {
                        draggingId?.let { dragged -> reorder(dragged, index, direction = null) }
                        draggingId = null
                    },
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun SubSeriesRow(
    child: SeriesCandidate,
    canReorder: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    isDragging: Boolean,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrop: () -> Unit,
) {
    Li(attrs = {
        classes("sh-sub")
        if (isDragging) classes("is-lifted")
        attr("data-sub-id", child.id.value)
        if (canReorder) {
            addEventListener("dragover") { event -> event.preventDefault() }
            addEventListener("drop") { event ->
                event.preventDefault()
                onDrop()
            }
        }
    }) {
        // The handle is the drag source; the buttons beside it are the keyboard route, so the
        // handle itself is presentational and out of the tab order.
        Span(attrs = {
            classes("sh-grip")
            attr("aria-hidden", "true")
            if (canReorder) {
                attr("draggable", "true")
                addEventListener("dragstart") { event ->
                    (event as? DragEvent)?.dataTransfer?.setData("text/plain", child.id.value)
                    onDragStart()
                }
                addEventListener("dragend") { onDragEnd() }
            }
        }) { Icon(WebIcon.Grip, size = ICON) }
        Div(attrs = { classes("sh-sub-text") }) {
            Span(attrs = { classes("sh-sub-n") }) { Text(child.displayName) }
            Span(attrs = { classes("sh-sub-m") }) { Text(seriesBookCount(child.bookCount)) }
        }
        Button(
            kind = ButtonKind.Icon,
            size = ButtonSize.Sm,
            // en.json's shelf.move_earlier
            label = "Move earlier",
            enabled = canReorder && !isFirst,
            onClick = onEarlier,
            attrs = { classes("sh-earlier") },
        ) { Icon(WebIcon.ArrowUp, size = ICON) }
        Button(
            kind = ButtonKind.Icon,
            size = ButtonSize.Sm,
            // en.json's shelf.move_later
            label = "Move later",
            enabled = canReorder && !isLast,
            onClick = onLater,
            attrs = { classes("sh-later") },
        ) { Icon(WebIcon.ArrowDown, size = ICON) }
    }
}

private const val ICON = 16
