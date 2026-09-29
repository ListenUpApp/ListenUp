package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.PageHeader
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * The chrome every signed-out screen sits in: a brand panel beside a centred form column.
 *
 * Both layouts of the comps (`AuthSignIn` and `AuthSignInDesktop`) are the same DOM — the brand
 * panel always renders and `css/07-auth.css` hides it below the breakpoint. That is the same one-mechanism
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
                PageHeader(title = title, subtitle = subtitle, display = true)
                content()
            }
        }
    }
}

/**
 * A failure that is about the whole attempt rather than one field — bad credentials, a server
 * refusal, a dead invite.
 *
 * `role="alert"` because it appears after a submit, away from focus: without it a screen reader user
 * presses the button and hears nothing. A problem with one field belongs on that field instead
 * (`Field`'s `errorText`), where the input can point at it.
 */
@Composable
internal fun FormAlert(content: @Composable () -> Unit) {
    Div(attrs = {
        classes("auth-err")
        attr("role", "alert")
    }) { content() }
}

/** [FormAlert] for a message that is only words. */
@Composable
internal fun FormAlert(message: String) {
    FormAlert { Text(message) }
}

private const val BRAND_HEADLINE = "Thousands of audiobooks. One beautiful library."

private const val BRAND_SUBTITLE =
    "Stream or download from your own ListenUp server — pick up on any device, right where you left off."
