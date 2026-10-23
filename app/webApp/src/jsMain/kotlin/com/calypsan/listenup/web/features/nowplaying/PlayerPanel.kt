package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.MenuAnchor
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.focusAsLanding
import com.calypsan.listenup.web.design.focusIsLost
import com.calypsan.listenup.web.design.focusWithoutScrolling
import com.calypsan.listenup.web.design.placeAgainst
import com.calypsan.listenup.web.design.reopenInTopLayer
import com.calypsan.listenup.web.design.showInTopLayer
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event

/** Where a player panel opens (spec §3.8): a bottom dialog on the phone page, a popover against its opener on wide. */
internal sealed interface PanelPlacement {
    /** The phone page: a modal bottom `<dialog>` with Close. */
    data object Sheet : PanelPlacement

    /** The bar or the wide More: a popover hung from [opener], focus returned to it on close. */
    data class Anchored(
        val opener: HTMLElement,
    ) : PanelPlacement
}

/**
 * The width from which a player panel opens as a popover from its opener rather than as a bottom sheet (open question 4,
 * C9's default): the page's own breakpoint, where the bar becomes the transport. Between 760 and 1023px the bar is the
 * only transport (PR 3 C8), so its panels hang from it there too.
 */
internal const val PANEL_POPOVER_MIN_WIDTH_PX = 760

/**
 * The placement a panel opened from [opener] takes: a popover against it when [opener]'s own window is at least
 * [PANEL_POPOVER_MIN_WIDTH_PX] wide, a bottom sheet below that, or when nothing opened it. Asked of the opener's window,
 * not the app's, so it answers to the viewport the opener is actually in.
 */
internal fun panelPlacementFor(opener: HTMLElement?): PanelPlacement {
    val view = opener?.ownerDocument?.defaultView ?: return PanelPlacement.Sheet
    val wide = view.matchMedia("(min-width: ${PANEL_POPOVER_MIN_WIDTH_PX}px)").matches
    return if (wide) PanelPlacement.Anchored(opener) else PanelPlacement.Sheet
}

/**
 * The shell every player panel wears (§3.8, drift row 30), in one of two placements.
 *
 * - [PanelPlacement.Sheet]: the app's one [ModalDialog] — focus trap, inert page, Escape — docked to the bottom and
 *   rounded on top (`.panel-sheet`), with Close beside the heading (`Sheets-WebPhone`).
 * - [PanelPlacement.Anchored]: a non-modal `role="dialog"` popover in the top layer, placed against its opener by the
 *   same [placeAgainst] every menu uses. Focus moves to its heading as it opens; Escape, Close or a press outside closes
 *   it; Tab out of it closes it too, since a popover traps nothing. Escape and Close hand focus back to the opener.
 *
 * Web's answer to `PlayerPanelScaffold`. Every player panel is a place you visit and leave, so both placements carry
 * Close.
 */
@Composable
internal fun PlayerPanel(
    open: Boolean,
    title: String,
    /** The panel's own class (`speed-dlg`, …): its width and spacing live there, and it names the heading's id. */
    panelClass: String,
    placement: PanelPlacement,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!open) return
    when (placement) {
        PanelPlacement.Sheet -> {
            ModalDialog(
                open = true,
                title = title,
                onDismiss = onDismiss,
                panelClass = panelClass,
                extraClass = SHEET_CLASS,
                headerAction = { CloseButton(onDismiss) },
            ) { content() }
        }

        is PanelPlacement.Anchored -> {
            AnchoredPanel(title, panelClass, placement.opener, onDismiss, content)
        }
    }
}

@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    Button(kind = ButtonKind.Secondary, onClick = { onDismiss() }, attrs = { classes("dlg-close") }) {
        Text(NowPlayingWords.CLOSE)
    }
}

/** Whether closing should hand focus back to the opener: not when the reader pressed or tabbed somewhere else. */
private class PanelExit {
    var returnsFocus = true
}

@Composable
private fun AnchoredPanel(
    title: String,
    panelClass: String,
    opener: HTMLElement,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val titleId = "$panelClass-title"
    val dismiss by rememberUpdatedState(onDismiss)
    val exit = remember { PanelExit() }
    val node = remember { PanelNode() }

    Div(attrs = {
        classes(POPOVER_CLASS, panelClass)
        attr("role", "dialog")
        attr("aria-labelledby", titleId)
        // Manual: the panel keeps its own outside-press, Escape and Tab-out handling.
        attr("popover", "manual")
        onKeyDown { event ->
            if (event.key == "Escape") {
                // Taken here, so the page behind does not close on the same key (NowPlayingRoute reads defaultPrevented).
                event.preventDefault()
                dismiss()
            }
        }
        onFocusOut { event ->
            val next = event.relatedTarget as? Node
            // Focus leaving for somewhere outside (Tab past the last control): the reader has moved on. A null
            // relatedTarget is a control vanishing under focus as the panel re-renders, which is not leaving; and the
            // opener's own press toggles the panel itself.
            if (next != null && node.element?.contains(next) == false && !opener.contains(next)) {
                exit.returnsFocus = false
                dismiss()
            }
        }
        ref { panel ->
            node.element = panel
            panel.showInTopLayer()
            val place = { placeAgainst(panel, MenuAnchor.of(opener)) }
            place()
            (panel.querySelector("#$titleId") as? HTMLElement)?.focusWithoutScrolling()

            val onMove: (Event) -> Unit = { place() }
            val onPointerDown: (Event) -> Unit = { event ->
                val target = event.target as? Node
                if (target != null && !panel.contains(target) && !opener.contains(target)) {
                    exit.returnsFocus = false
                    dismiss()
                }
            }
            val view = panel.ownerDocument?.defaultView
            val document = panel.ownerDocument
            // Capture, so a scroll inside any box moves the panel with its opener, as a menu does.
            view?.addEventListener("scroll", onMove, true)
            view?.addEventListener("resize", onMove)
            document?.addEventListener("pointerdown", onPointerDown)
            val heading = opener.closest("section")?.querySelector("h1, h2, h3") as? HTMLElement
            onDispose {
                node.element = null
                view?.removeEventListener("scroll", onMove, true)
                view?.removeEventListener("resize", onMove)
                document?.removeEventListener("pointerdown", onPointerDown)
                if (exit.returnsFocus) returnFocus(opener, heading)
            }
        }
    }) {
        Div(attrs = { classes("dlg-head") }) {
            H2(attrs = {
                classes("dlg-t")
                attr("id", titleId)
                attr("tabindex", "-1")
            }) { Text(title) }
            CloseButton(onDismiss)
        }
        content()
    }
    // Compose re-applies every attribute when any changes, and taking `popover` off closes it; and the content can
    // change size (Sleep's choices give way to its countdown), so it is shown and placed again after every pass.
    SideEffect {
        node.element?.let { panel ->
            panel.reopenInTopLayer()
            placeAgainst(panel, MenuAnchor.of(opener))
        }
    }
}

/** The popover's element, captured by `ref`. */
private class PanelNode {
    var element: HTMLElement? = null
}

/**
 * After the closing update has landed, and only if focus is then nowhere: back to [opener], or — if the close removed
 * it — to the heading of the section it was in, as [ModalDialog] does.
 */
private fun returnFocus(
    opener: HTMLElement,
    sectionHeading: HTMLElement?,
) {
    window.setTimeout({
        if (!focusIsLost(opener)) return@setTimeout
        when {
            opener.isConnected -> opener.focus()
            sectionHeading?.isConnected == true -> focusAsLanding(sectionHeading)
        }
    }, 0)
}

/** The phone page's bottom sheet: docked to the bottom, rounded on top, scrolling inside itself. */
private const val SHEET_CLASS = "panel-sheet"

/** The wide popover. */
private const val POPOVER_CLASS = "panel-pop"
