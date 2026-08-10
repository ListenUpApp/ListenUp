package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * The chrome every signed-out screen sits in: a brand panel beside a centred form column.
 *
 * Both layouts of the comps (`AuthSignIn` and `AuthSignInDesktop`) are the same DOM — the brand
 * panel always renders and `web.css` hides it below the breakpoint. That is the same one-mechanism
 * rule the shell's rail follows, and for the same reason: a Kotlin-side width check needs a resize
 * listener and will disagree with the sheet at the boundary.
 *
 * The comps put tilted cover tiles under the headline. They are deliberately absent here: the
 * comp's tiles carry real titles in bright accent colours, and reduced to bare gradients they
 * read as artwork that failed to load — which is a worse first impression than empty space. Words
 * the panel can actually stand behind beat a gesture at covers this server may not even have yet.
 */
@Composable
fun AuthLayout(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    content: @Composable () -> Unit,
) {
    Div(attrs = { classes("auth") }) {
        Div(attrs = { classes("auth-brand") }) {
            H2(attrs = { classes("auth-hd") }) { Text(BRAND_HEADLINE) }
            P(attrs = { classes("auth-sub") }) { Text(BRAND_SUBTITLE) }
        }

        Div(attrs = { classes("auth-form") }) {
            Div(attrs = { classes("auth-col") }) {
                badge?.let { Div(attrs = { classes("badge") }) { Text(it) } }
                H1(attrs = { classes("auth-t") }) { Text(title) }
                subtitle?.let { P(attrs = { classes("auth-st") }) { Text(it) } }
                content()
            }
        }
    }
}

private const val BRAND_HEADLINE = "Thousands of audiobooks. One beautiful library."

private const val BRAND_SUBTITLE =
    "Stream or download from your own ListenUp server — pick up on any device, right where you left off."
