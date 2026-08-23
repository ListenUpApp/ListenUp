package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.web.WebAppRoot
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.features.bookdetail.OpenBookDetail
import com.calypsan.listenup.web.features.bookedit.OpenBookEdit
import com.calypsan.listenup.web.features.library.OpenLibrary
import com.calypsan.listenup.web.features.nowplaying.OpenPlayback
import com.calypsan.listenup.web.nav.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * The root of the ListenUp web body: `AuthState` decides whether the reader sees an auth screen or
 * the app.
 *
 * Auth screens have no URL. `AuthState` is already the sole navigation driver on Android and iOS,
 * and giving these screens routes would make the URL a second source of truth for the same
 * question. The concrete payoff is that [router] stays mounted underneath holding whatever the
 * reader asked for — so `/book/123` opened while signed out renders for real the moment login
 * succeeds, with no `?next=` plumbing and no dead `/login` entry in browser history.
 *
 * [WebAppSurface] lives here rather than in [WebAppRoot] because every branch needs it and only
 * one of them is the shell.
 */
@Composable
fun AuthGate(
    authGraph: AuthGraph,
    router: Router,
    openBookDetail: OpenBookDetail,
    openBookEdit: OpenBookEdit,
    openLibrary: OpenLibrary,
    openPlayback: OpenPlayback,
    observeIsAdmin: () -> Flow<Boolean>,
) {
    val scope = rememberCoroutineScope()
    val authState by authGraph.authState.collectAsState()

    LaunchedEffect(Unit) {
        // A failed probe must not take the page down with it. Same reasoning as the server-URL
        // seed in Main.kt: rendering is downstream of this, so an escaping exception is a white
        // page with a console stacktrace — the worst possible way to report "we could not work
        // out whether you are signed in", a condition a reload or a manual sign-in can fix.
        try {
            authGraph.initialize()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            console.warn("Failed to resolve auth state: ${e.message}")
        }
    }

    WebAppSurface {
        when (val state = authState) {
            AuthState.Initializing,
            AuthState.CheckingServer,
            // Unreachable on web — main() seeds the server URL from the page origin before the
            // composition mounts. Rendered as the boot surface rather than thrown on: an
            // unreachable state should be inert, not fatal.
            AuthState.NeedsServerUrl,
            -> {
                AuthBoot()
            }

            AuthState.NeedsSetup -> {
                SetupBranch(authGraph)
            }

            is AuthState.NeedsLogin -> {
                LoginBranch(authGraph, state.openRegistration)
            }

            is AuthState.PendingApproval -> {
                PendingApprovalBranch(authGraph, state.userId.value, state.email)
            }

            // SessionLapsed rides with Authenticated exactly as AuthNavigation.kt:131 does. Its
            // documented "sign in to sync" affordance is deferred on every platform; web does not
            // get to invent a re-auth UX the native clients do not have.
            is AuthState.Authenticated,
            is AuthState.SessionLapsed,
            -> {
                WebAppRoot(
                    router = router,
                    openBookDetail = openBookDetail,
                    openBookEdit = openBookEdit,
                    openLibrary = openLibrary,
                    onSignOut = { scope.launch { authGraph.signOut() } },
                    openPlayback = openPlayback,
                    observeIsAdmin = observeIsAdmin,
                )
            }
        }
    }
}

@Composable
private fun SetupBranch(authGraph: AuthGraph) {
    val session = remember { authGraph.openSetup() }
    DisposableEffect(session) { onDispose { session.close() } }

    AuthLayout(
        title = "Create admin account",
        subtitle = "Set up your ListenUp server by creating the first admin account.",
        badge = "Server administrator",
    ) {
        SetupForm(state = session.state.collectAsState().value, onSubmit = session.submit)
    }
}

/**
 * Sign-in, plus registration as a sub-state of it.
 *
 * `showingRegister` is keyed on the branch, so leaving `NeedsLogin` for any reason discards it —
 * signing out later must land on sign-in, not on a registration form abandoned minutes ago.
 */
@Composable
private fun LoginBranch(
    authGraph: AuthGraph,
    openRegistration: Boolean,
) {
    var showingRegister by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            authGraph.refreshOpenRegistration()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            console.warn("Failed to refresh open registration: ${e.message}")
        }
    }

    if (showingRegister) {
        val session = remember { authGraph.openRegister() }
        DisposableEffect(session) { onDispose { session.close() } }

        AuthLayout(title = "Create account", subtitle = "Ask this server's admin for access.") {
            RegisterForm(
                state = session.state.collectAsState().value,
                onSubmit = session.submit,
                onBack = { showingRegister = false },
            )
        }
    } else {
        val session = remember { authGraph.openLogin() }
        DisposableEffect(session) { onDispose { session.close() } }

        AuthLayout(title = "Sign in", subtitle = "Pick up right where you left off in your audiobook library.") {
            LoginForm(
                state = session.state.collectAsState().value,
                openRegistration = openRegistration,
                onSubmit = session.submit,
                onRegister = { showingRegister = true },
            )
        }
    }
}

@Composable
private fun PendingApprovalBranch(
    authGraph: AuthGraph,
    userId: String,
    email: String,
) {
    val session = remember(userId, email) { authGraph.openPendingApproval(userId, email) }
    DisposableEffect(session) { onDispose { session.close() } }

    AuthLayout(title = "Waiting for approval", subtitle = "Your account needs an admin to let it in.") {
        PendingApprovalPanel(
            state = session.state.collectAsState().value,
            email = email,
            onCheckStatus = session.checkStatus,
            onCancel = session.cancelRegistration,
            onAcknowledge = session.acknowledgeApproval,
        )
    }
}

/**
 * The moment before the app knows who you are.
 *
 * Deliberately not a login form: showing one to a reader who has a valid stored session, for the
 * fraction of a second it takes to read it, is the most visible way this gate can lie.
 */
@Composable
private fun AuthBoot() {
    Div(attrs = { classes("auth-boot") }) { Text("Checking your session…") }
}
