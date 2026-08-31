package com.calypsan.listenup.web.features.nowplaying

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement

/**
 * Closes any modal a spec opened, so it does not hold focus over every spec that follows.
 *
 * Shared rather than copied per spec: a player panel left open is not a local failure. `showModal`
 * makes the rest of the page inert, so one spec forgetting this turns into a cascade of unrelated
 * ones failing to click anything — which is exactly how a leaked chapter dialog once took three
 * `CommandPaletteTest` cases down with it.
 *
 * Deliberately not a `mount` helper alongside it: the three specs here mount differently on
 * purpose (one tracks its hosts for teardown, two wrap in `WebAppSurface` for themed styling), and
 * folding those into one shape would hide a real difference rather than remove a duplicate one.
 */
internal fun HTMLElement.closeDialogs() {
    val dialogs = querySelectorAll("dialog")
    for (i in 0 until dialogs.length) {
        (dialogs.item(i) as? HTMLDialogElement)?.takeIf { it.open }?.close()
    }
}

/**
 * The button whose visible text is exactly [text], or null.
 *
 * By text rather than by position, so a spec says which control it means. An index into
 * `querySelectorAll` reads as "the third one", which stops being true the moment a rung is added
 * to a ladder — and passes for the wrong reason in the meantime.
 */
internal fun HTMLElement.buttonSaying(text: String): HTMLButtonElement? {
    val buttons = querySelectorAll("button")
    for (i in 0 until buttons.length) {
        val button = buttons.item(i) as? HTMLButtonElement ?: continue
        if (button.textContent?.trim() == text) return button
    }
    return null
}

/**
 * Whether this dialog is open *modally* — top layer, focus trapped, page behind inert.
 *
 * ⛔ Not [HTMLDialogElement.open], which is what `showModal()` and a bare `open` attribute both
 * set. A spec asserting that is green against a dialog with no focus trap, no inert page and no
 * Escape-to-close — the three things the real element is used for. Found by sabotage: swapping
 * `showModal()` for `setAttribute("open", "")` broke every one of those guarantees and failed
 * nothing.
 *
 * `:modal` is the pseudo-class that separates them, and it is exactly the distinction being made.
 */
internal fun HTMLDialogElement.isModal(): Boolean = matches(":modal")
