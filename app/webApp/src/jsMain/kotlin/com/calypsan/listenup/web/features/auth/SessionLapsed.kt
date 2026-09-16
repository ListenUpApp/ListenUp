package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.client.presentation.connection.ConnectionHealthUi
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

/**
 * The shell's one connection-health banner, rendered from the shared projection.
 *
 * ⛔ One projection decides this, not two signals. `ConnectionHealthUi.SessionExpired` and web's old
 * `AuthState.SessionLapsed` check are the same fact — `ConnectionHealthStore` derives the former
 * from exactly the latter's flow — so reading both would show a reader two banners about one
 * expired session.
 *
 * `Hidden` covers the unreachable server too, deliberately: offline-first means an unreachable
 * server is never ambient banner noise. Point-of-need surfaces (the offline-search notice, for one)
 * say so where it matters instead.
 */
@Composable
fun ConnectionHealthBanner(
    state: ConnectionHealthUi,
    authGraph: AuthGraph,
    onDismissOutdated: () -> Unit,
) {
    when (state) {
        ConnectionHealthUi.Hidden -> Unit

        // Not dismissible, and that is the whole design: this banner is the only way back in.
        ConnectionHealthUi.SessionExpired -> SessionLapsedBanner(authGraph)

        is ConnectionHealthUi.Outdated -> OutdatedBanner(state, onDismissOutdated)
    }
}

/**
 * A version-mismatch hint: what this client is, what the server is, and what it might cost.
 *
 * Dismissible, unlike a lapsed session — nothing is broken, some things may simply not sync, and a
 * reader who has read it once should not keep being told.
 */
@Composable
private fun OutdatedBanner(
    state: ConnectionHealthUi.Outdated,
    onDismiss: () -> Unit,
) {
    Div(attrs = {
        classes("lapse", "is-hint")
        attr("role", "status")
        attr("aria-live", "polite")
    }) {
        Div(attrs = { classes("lapse-ico") }) { Icon(WebIcon.ArrowUp, size = LAPSE_ICON) }
        Div(attrs = { classes("lapse-text") }) {
            Span(attrs = { classes("lapse-t") }) { Text("Update available") }
            Span(attrs = { classes("lapse-b") }) {
                Text("App ${state.clientVersion} / server ${state.serverVersion}. Some features may not sync.")
            }
        }
        Button(attrs = {
            classes("btn-o", "lapse-act")
            attr("type", "button")
            attr("aria-label", "Dismiss update hint")
            onClick { onDismiss() }
        }) { Text("Dismiss") }
    }
}
