package com.calypsan.listenup.web.design

import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.HTMLButtonElement

/**
 * Disables a button when [condition] holds.
 *
 * `disabled` is a boolean attribute: what makes a control disabled is the attribute being *present*,
 * not its value — so every site writes the same empty string, and writing it by hand is three lines
 * of `if` at every call site that can each get the sense backwards.
 *
 * Lifted out of the four pages that had grown a private copy. The rule it encodes is one fact about
 * HTML, and one fact should not have four homes.
 */
fun AttrsScope<HTMLButtonElement>.disabledWhen(condition: Boolean) {
    if (condition) attr("disabled", "")
}
