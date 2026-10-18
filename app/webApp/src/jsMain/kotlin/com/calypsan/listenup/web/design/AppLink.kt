package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.A
import androidx.compose.web.events.SyntheticMouseEvent
import org.w3c.dom.HTMLAnchorElement

/**
 * An in-app link: a real `<a href>`, so it is a tab stop, announced as a link, and opens in a new tab on
 * a middle or modified click, while a plain click navigates in place through [onOpen].
 */
@Composable
fun AppLink(
    href: String,
    onOpen: () -> Unit,
    attrs: (AttrsScope<HTMLAnchorElement>.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    A(href = href, attrs = {
        attrs?.invoke(this)
        onClick { event ->
            if (event.isPlainPrimaryClick()) {
                event.preventDefault()
                onOpen()
            }
        }
    }) { content() }
}

/** A click the app routes itself; anything modified is the reader asking the browser for a new tab. */
internal fun SyntheticMouseEvent.isPlainPrimaryClick(): Boolean =
    button == PRIMARY_BUTTON && !ctrlKey && !metaKey && !shiftKey && !altKey

private const val PRIMARY_BUTTON: Short = 0
