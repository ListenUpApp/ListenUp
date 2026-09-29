package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text

/**
 * An action that reads as a link: "Forgot password?", "Create account", "Back to sign in".
 *
 * A real `<button type="button">` wearing `.lnk`. These were `<span onClick>`s, which look identical
 * and do nothing for anyone without a mouse: no tab stop, no Enter, announced as plain text. On the
 * auth screens that meant a keyboard reader could not recover a password, register, or redeem an
 * invite — the screens with no other route past them.
 *
 * A button rather than an `<a>` because none of these has an address: each one swaps the panel the
 * auth gate is showing, which is an action, and an `href` it could not honour would be a lie.
 * `type="button"` because most of them sit inside a `<form>`, where a bare `<button>` submits it.
 */
@Composable
fun LinkButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(attrs = {
        classes("lnk")
        attr("type", "button")
        onClick { onClick() }
    }) { Text(label) }
}
