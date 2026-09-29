package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonLink
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Text

/**
 * What an address nothing lives at shows: that it was not found, and the way home.
 *
 * The way home is a real `<a href="/">` — it has an address, so a middle-click or "copy link" works —
 * and a plain click is routed in the app, as the sidebar's links are, rather than reloading it.
 */
@Composable
internal fun NotFoundPage(onGoHome: () -> Unit) {
    PageHeader(title = "Page not found")
    EmptyState(
        title = "Nothing lives at this address",
        body = "The link may be out of date, or the address mistyped.",
        icon = WebIcon.Compass,
        action = {
            ButtonLink(href = "/", kind = ButtonKind.Primary, attrs = {
                onClick { event ->
                    if (event.isPlainPrimaryClick()) {
                        event.preventDefault()
                        onGoHome()
                    }
                }
            }) { Text("Go to Home") }
        },
    )
}
