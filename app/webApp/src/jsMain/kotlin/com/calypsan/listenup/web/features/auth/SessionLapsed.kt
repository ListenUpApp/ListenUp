package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The way back in after a session lapses.
 *
 * ⛔ **The shell stays mounted.** A lapse is not a sign-out: the library is in OPFS and still reads,
 * and playback of an already-loaded book keeps working. Throwing the reader back to a login wall
 * would take away everything that still functions in order to report that one thing does not. Both
 * native clients make the same call.
 *
 * ⛔ **The banner is not dismissible, and the sheet is.** They are different promises. The banner is
 * the only route to sign-in from an authenticated shell, so a close button removes the reader's sole
 * way back to a working session — Android's own banner says exactly this, having been bitten by a
 * dismissal that survived every navigation. The sheet over it is escapable because a reader who
 * opened it to look is entitled to go back to reading.
 *
 * Nothing here decides when the lapse ends: signing in flips `authState`, the branch stops rendering
 * this, and banner and sheet go together.
 */
@Composable
fun SessionLapsedBanner(authGraph: AuthGraph) {
    var signingIn by remember { mutableStateOf(false) }

    Div(attrs = {
        classes("lapse")
        // Announced, not merely drawn: the lapse arrives while the reader is doing something else,
        // and a coloured strip appearing above the page says nothing to a screen reader.
        attr("role", "status")
        attr("aria-live", "polite")
    }) {
        Div(attrs = { classes("lapse-ico") }) { Icon(WebIcon.Person, size = LAPSE_ICON) }
        Div(attrs = { classes("lapse-text") }) {
            Span(attrs = { classes("lapse-t") }) { Text("Signed out") }
            // ⛔ Not the shared string verbatim: it promises "your library and downloads still
            // work", and this client has no downloads. Saying so would be a lie in the one place a
            // reader is being told what still works.
            Span(attrs = { classes("lapse-b") }) { Text("Sign in to sync — your library still works.") }
        }
        Button(attrs = {
            classes("btn-c", "lapse-go")
            attr("type", "button")
            onClick { signingIn = true }
        }) { Text("Sign in") }
    }

    if (signingIn) {
        SignInSheet(authGraph = authGraph, onDismiss = { signingIn = false })
    }
}

/**
 * The sign-in form, over the shell rather than instead of it.
 *
 * Reuses [LoginForm] — the same form the signed-out route renders — so a fix to either reaches both.
 * Registration, forgotten passwords and invite codes are deliberately NOT offered here: this reader
 * has an account, and the question on the table is only whether they can prove it.
 */
@Composable
private fun SignInSheet(
    authGraph: AuthGraph,
    onDismiss: () -> Unit,
) {
    val session = remember { authGraph.openLogin() }
    DisposableEffect(session) { onDispose { session.close() } }

    ModalDialog(open = true, title = "Sign back in", onDismiss = onDismiss) {
        LoginForm(
            state = session.state.collectAsState().value,
            openRegistration = false,
            onSubmit = session.submit,
            onRegister = {},
            onForgotPassword = {},
            onClaimInvite = {},
        )
    }
}

private const val LAPSE_ICON = 20
