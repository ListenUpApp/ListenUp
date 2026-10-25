package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent

/**
 * Who answers a key first (spec §5.8: the palette and the player share one dispatcher so they cannot fight). A page
 * that owns keys of its own (the chapter editor's ← / → nudge a boundary) beats the palette, and the palette beats the
 * player.
 */
enum class KeyPriority {
    /** The page on screen: its own keys win on its own page. */
    Page,

    /** The command palette: ⌘K, `/`, and its keys while open. */
    Palette,

    /** The player's shortcuts: last, so they never take a key someone nearer the reader wanted. */
    Player,
}

/**
 * The one `window` `keydown` listener (spec §5.8). Handlers register with a [KeyPriority] and say whether they
 * consumed a press; the first that does wins, and the press stops there.
 *
 * One listener rather than one per feature, because three of them had grown (the palette, Now Playing's Esc and the
 * chapter editor), each with its own idea of when to stand down, and a global ← would have skipped the audio while
 * the editor nudged a boundary.
 */
class WindowKeys {
    private val handlers = mutableListOf<Registration>()

    /** Adds [handler] at [priority]; returns its removal. Handlers of one priority answer in registration order. */
    fun register(
        priority: KeyPriority,
        handler: (KeyboardEvent) -> Boolean,
    ): () -> Unit {
        val registration = Registration(priority, handler)
        handlers += registration
        return { handlers -= registration }
    }

    /** Offers [event] to each handler in priority order until one consumes it. */
    fun dispatch(event: KeyboardEvent) {
        handlers.sortedBy { it.priority.ordinal }.firstOrNull { it.handler(event) }
    }

    /** Listens on [window] until the returned removal runs. */
    fun install(): () -> Unit {
        val listener: (Event) -> Unit = { event -> (event as? KeyboardEvent)?.let(::dispatch) }
        window.addEventListener(KEYDOWN, listener)
        return { window.removeEventListener(KEYDOWN, listener) }
    }

    private class Registration(
        val priority: KeyPriority,
        val handler: (KeyboardEvent) -> Boolean,
    )
}

/** The shell's [WindowKeys], or null where a page is rendered without the shell (a spec of the page alone). */
val LocalWindowKeys = staticCompositionLocalOf<WindowKeys?> { null }

/**
 * Registers [handler] with the shell's [WindowKeys] for as long as the caller is composed. Without a shell (a spec
 * that renders one page) it listens on `window` itself, so the page's keys still work there.
 */
@Composable
fun WindowKeyHandler(
    priority: KeyPriority,
    handler: (KeyboardEvent) -> Boolean,
) {
    val keys = LocalWindowKeys.current
    val latest = rememberUpdatedState(handler)
    DisposableEffect(keys, priority) {
        val forward: (KeyboardEvent) -> Boolean = { event -> latest.value(event) }
        val remove =
            if (keys != null) {
                keys.register(priority, forward)
            } else {
                WindowKeys().apply { register(priority, forward) }.install()
            }
        onDispose { remove() }
    }
}

/**
 * Where a key press landed, as far as a shortcut cares (spec §5.8: "never while focus is in an editable field or a
 * dialog has its own handling").
 */
data class KeyTarget(
    /** A field the reader types in: every key is the field's. */
    val isEditable: Boolean = false,
    /** A dialog, a menu, a listbox or a popover has the keyboard: its own keys, Esc included, are its own. */
    val isInDialog: Boolean = false,
    /** A control Space and Enter activate (a button, a link, a radio, …): those two keys are the control's. */
    val activates: Boolean = false,
    /** Inside a widget the arrows move through (a radio group, a grid, a tab list, …): the arrows are the widget's. */
    val inComposite: Boolean = false,
) {
    /** Named targets. */
    companion object {
        /** Nothing in particular: the page itself. */
        val Body = KeyTarget()
    }
}

/**
 * True for a field the reader types in: an `input` (the range scrubber and number fields too, whose arrows and Space
 * are their own), a `textarea`, a `select`, editable content, or a text, search or combo box role. The one rule the
 * palette's `/` and the player's keys both stand down on.
 */
fun isEditableTarget(target: EventTarget?): Boolean = (target as? Element)?.closest(EDITABLE_SELECTOR) != null

/** Where [event] landed, for the player's key table. Reads the DOM; the table that judges it is pure. */
fun keyTargetOf(event: KeyboardEvent): KeyTarget {
    val element = event.target as? Element
    return KeyTarget(
        isEditable = isEditableTarget(element),
        isInDialog = element?.closest(DIALOG_SELECTOR) != null || document.querySelector(OPEN_MODAL_SELECTOR) != null,
        activates = element?.closest(ACTIVATES_SELECTOR) != null,
        inComposite = element?.closest(COMPOSITE_SELECTOR) != null,
    )
}

private const val KEYDOWN = "keydown"

private const val EDITABLE_SELECTOR =
    "input, textarea, select, [contenteditable]:not([contenteditable=false]), " +
        "[role=textbox], [role=searchbox], [role=combobox]"

private const val DIALOG_SELECTOR = "dialog, [role=dialog], [role=menu], [role=listbox], [popover]"

/** An open dialog or popover anywhere has the keyboard (a modal makes the page behind it inert): the keys are its own. */
private const val OPEN_MODAL_SELECTOR = "dialog[open], :popover-open"

private const val ACTIVATES_SELECTOR =
    "button, a[href], summary, [role=button], [role=link], [role=radio], [role=checkbox], [role=switch], " +
        "[role=tab], [role=option], [role=menuitem]"

private const val COMPOSITE_SELECTOR =
    "[role=radiogroup], [role=grid], [role=listbox], [role=tablist], [role=tree], [role=slider], [role=menubar], " +
        "[role=toolbar]"
