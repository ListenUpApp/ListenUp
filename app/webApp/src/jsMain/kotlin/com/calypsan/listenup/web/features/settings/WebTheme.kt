package com.calypsan.listenup.web.features.settings

import com.calypsan.listenup.client.domain.model.ThemeMode
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.MediaQueryList
import org.w3c.dom.events.Event

/** The attribute `web.css` keys its dark palette on. Absent means light. */
private const val THEME_ATTRIBUTE = "data-theme"

private const val DARK = "dark"

/** The query the browser answers "is the OS in dark mode?" with. */
internal const val DARK_SCHEME_QUERY = "(prefers-color-scheme: dark)"

/**
 * Whether the page should be dark, given the reader's choice and what the OS says.
 *
 * Pure so the one branch that matters — SYSTEM deferring to the OS while LIGHT and DARK ignore it —
 * is provable without a browser. The sheet has no `prefers-color-scheme` rule of its own, so this
 * function is the *only* thing that can make the OS preference matter.
 */
internal fun shouldUseDarkTheme(
    mode: ThemeMode,
    systemPrefersDark: Boolean,
): Boolean =
    when (mode) {
        ThemeMode.SYSTEM -> systemPrefersDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

/**
 * Applies [dark] to the document, which is the whole of how this app themes itself.
 *
 * `web.css` carries a full dark palette under `[data-theme="dark"]` and has always carried it —
 * nothing ever set the attribute, so every reader saw the light theme whatever their OS said. The
 * attribute is set on `documentElement` rather than the mount point because the sheet's rules are
 * written against the root, and because the page's own background sits above the mount.
 */
internal fun applyTheme(dark: Boolean) {
    if (dark) {
        document.documentElement?.setAttribute(THEME_ATTRIBUTE, DARK)
    } else {
        document.documentElement?.removeAttribute(THEME_ATTRIBUTE)
    }
}

/** The OS's current answer, or `false` where a browser will not say. */
internal fun systemPrefersDark(): Boolean = window.matchMedia(DARK_SCHEME_QUERY).matches

/**
 * Watches the OS preference and calls [onChange] when it flips.
 *
 * Registered for every mode, not only SYSTEM: someone on LIGHT who switches to SYSTEM should not
 * have to reload for the OS setting to take effect, and the listener is cheaper than the bookkeeping
 * to add and remove it as the mode changes. Returns the teardown.
 */
internal fun watchSystemTheme(onChange: (Boolean) -> Unit): () -> Unit {
    val query: MediaQueryList = window.matchMedia(DARK_SCHEME_QUERY)
    val listener: (Event) -> Unit = { onChange(query.matches) }
    query.addEventListener("change", listener)
    return { query.removeEventListener("change", listener) }
}
