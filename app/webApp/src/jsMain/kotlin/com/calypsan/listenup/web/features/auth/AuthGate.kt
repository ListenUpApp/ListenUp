package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.web.features.admin.OpenAdmin
import com.calypsan.listenup.web.features.devices.OpenDevices
import com.calypsan.listenup.web.features.settings.OpenSettings
import com.calypsan.listenup.web.features.settings.watchSystemTheme
import com.calypsan.listenup.web.features.settings.systemPrefersDark
import com.calypsan.listenup.web.features.settings.shouldUseDarkTheme
import com.calypsan.listenup.web.features.settings.applyTheme
import com.calypsan.listenup.client.domain.model.ThemeMode
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
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.web.design.ToastHost
import com.calypsan.listenup.web.design.ToastQueue
import com.calypsan.listenup.web.design.ToastTone
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.design.toastText
import com.calypsan.listenup.web.features.bookdetail.OpenBookDetail
import com.calypsan.listenup.web.features.bookedit.OpenBookEdit
import com.calypsan.listenup.web.features.contributordetail.OpenContributorDetail
import com.calypsan.listenup.web.features.notifications.OpenNotificationBell
import com.calypsan.listenup.web.features.notifications.OpenNotificationPrefs
import com.calypsan.listenup.web.features.notifications.OpenNotifications
import com.calypsan.listenup.web.features.setup.LibrarySetupPage
import com.calypsan.listenup.web.features.setup.OpenLibrarySetup
import com.calypsan.listenup.web.features.seriesdetail.OpenSeriesDetail
import com.calypsan.listenup.web.features.contributors.OpenContributors
import com.calypsan.listenup.web.features.library.OpenLibrary
import com.calypsan.listenup.web.features.nowplaying.OpenPlayback
import com.calypsan.listenup.web.features.discover.OpenDiscover
import com.calypsan.listenup.web.features.home.OpenHome
import com.calypsan.listenup.web.features.shelf.OpenShelfDetail
import com.calypsan.listenup.web.features.shelf.OpenShelfEdit
import com.calypsan.listenup.web.features.search.OpenSearch
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
    openContributorDetail: OpenContributorDetail,
    openSeriesDetail: OpenSeriesDetail,
    openNotifications: OpenNotifications,
    openNotificationPrefs: OpenNotificationPrefs,
    openLibrarySetup: OpenLibrarySetup,
    openNotificationBell: OpenNotificationBell,
    openContributors: OpenContributors,
    openLibrary: OpenLibrary,
    openHome: OpenHome,
    openDiscover: OpenDiscover,
    openSettings: OpenSettings,
    openDevices: OpenDevices,
    openAdmin: OpenAdmin,
    openShelfDetail: OpenShelfDetail,
    openShelfEdit: OpenShelfEdit,
    openSearch: OpenSearch,
    openPlayback: OpenPlayback,
    observeIsAdmin: () -> Flow<Boolean>,
    observeThemeMode: () -> Flow<ThemeMode>,
    initialInviteCode: String? = null,
    observeErrors: () -> Flow<AppError>,
) {
    val scope = rememberCoroutineScope()
    val authState by authGraph.authState.collectAsState()
    var pendingInviteCode by remember { mutableStateOf(initialInviteCode) }

    // Above the auth branch, not inside the shell: someone who prefers dark should get it on the
    // sign-in screen too, and a theme that only arrives after login is a flash of the wrong one.
    ThemeEffect(observeThemeMode)

    // Same reasoning, and the same level: a failed sign-in, a rate limit, a server that cannot be
    // reached — those are all emitted by shared ViewModels the signed-out screens drive, so a
    // toast layer that only existed inside the shell would drop exactly the errors a reader who
    // cannot get in most needs to see.
    val toasts = remember { ToastQueue() }
    LaunchedEffect(Unit) {
        observeErrors().collect { error -> toasts.show(error.toastText(), ToastTone.Failure) }
    }

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
                // One-shot, and held ABOVE the branch so it survives the branch but not its own
                // use. Read straight from the parameter, a code redeemed minutes ago would
                // re-open the claim pane every time the reader came back to sign-in — the branch
                // remembers its pane, but it re-derives the opening one each time it remounts.
                LoginBranch(
                    authGraph = authGraph,
                    openRegistration = state.openRegistration,
                    initialInviteCode = pendingInviteCode,
                    onInviteConsumed = { pendingInviteCode = null },
                )
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
                LibrarySetupGate(openLibrarySetup) {
                    WebAppRoot(
                        router = router,
                        openBookDetail = openBookDetail,
                        openBookEdit = openBookEdit,
                        openContributorDetail = openContributorDetail,
                        openSeriesDetail = openSeriesDetail,
                        openNotifications = openNotifications,
                        openNotificationPrefs = openNotificationPrefs,
                        openNotificationBell = openNotificationBell,
                        openContributors = openContributors,
                        openLibrary = openLibrary,
                        openHome = openHome,
                        openDiscover = openDiscover,
                        openSettings = openSettings,
                        openDevices = openDevices,
                        openAdmin = openAdmin,
                        openShelfDetail = openShelfDetail,
                        openShelfEdit = openShelfEdit,
                        openSearch = openSearch,
                        onSignOut = { scope.launch { authGraph.signOut() } },
                        openPlayback = openPlayback,
                        observeIsAdmin = observeIsAdmin,
                    )
                }
            }
        }

        // Last inside the surface, so it paints over whichever branch is showing.
        ToastHost(toasts)
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
 * Holds back the app while the server has no audiobook folders.
 *
 * A signed-in admin whose server was never pointed at anything reaches a shell with an empty
 * library and no control anywhere in it that would help — so the wizard comes first, exactly as it
 * does on Android and iOS. Everyone else (and every non-admin, whose `getSetupStatus` reports no
 * setup needed) falls straight through to [content] having rendered nothing.
 *
 * ⛔ **The gate closes on the `Finished` event, not on the state.** `LibrarySetupViewModel` does
 * not flip `needsSetup` back to false when `completeSetup` succeeds — its last act is to start the
 * scan and emit the one-shot. A gate that re-read `needsSetup` would therefore show the wizard
 * again, over a library that was just configured, forever.
 *
 * While the status probe is in flight neither branch renders: showing the app for the half-second
 * before the answer arrives would flash an empty library at precisely the person who is about to
 * be told why it is empty.
 */
@Composable
private fun LibrarySetupGate(
    openLibrarySetup: OpenLibrarySetup,
    content: @Composable () -> Unit,
) {
    val session = remember { openLibrarySetup() }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsState()

    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        session.navActions.collect { finished = true }
    }

    when {
        finished || (!state.needsSetup && !state.isCheckingStatus) -> {
            content()
        }

        state.isCheckingStatus -> {
            AuthBoot()
        }

        else -> {
            LibrarySetupPage(
                state = state,
                onOpenFolder = session.onOpenFolder,
                onNavigateUp = session.onNavigateUp,
                onToggleFolder = session.onToggleFolder,
                onComplete = session.onComplete,
                onDismissError = session.onDismissError,
            )
        }
    }
}

/** Which of `NeedsLogin`'s four screens is showing. See [LoginBranch]. */
private enum class LoginPane {
    SignIn,
    Register,
    Forgot,
    Invite,
}

/**
 * Sign-in, plus the three screens that hang off it: registration, password recovery, and
 * redeeming an invite.
 *
 * [LoginPane] is keyed on the branch, so leaving `NeedsLogin` for any reason discards it —
 * signing out later must land on sign-in, not on a form abandoned minutes ago.
 *
 * All four are sub-states of one `AuthState` rather than routes of their own, for the reason
 * [AuthGate] gives: `AuthState` is the sole navigation driver, and a URL for "I forgot my
 * password" would be a second source of truth for a question it cannot answer.
 *
 * [initialInviteCode] is the one thing here that genuinely arrives from the URL, and it is passed
 * as *data* rather than routed on. A code is a payload someone shows up holding, not a place — so
 * it chooses the opening pane and is handed straight to the ViewModel, and the gate goes on
 * deriving every screen from `AuthState` alone. `Main.kt` strips it from the address bar on the
 * way in; see [com.calypsan.listenup.web.takeInviteCode].
 */
@Composable
private fun LoginBranch(
    authGraph: AuthGraph,
    openRegistration: Boolean,
    initialInviteCode: String? = null,
    onInviteConsumed: () -> Unit = {},
) {
    var pane by remember { mutableStateOf(if (initialInviteCode != null) LoginPane.Invite else LoginPane.SignIn) }

    LaunchedEffect(Unit) {
        try {
            authGraph.refreshOpenRegistration()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            console.warn("Failed to refresh open registration: ${e.message}")
        }
    }

    when (pane) {
        LoginPane.Register -> {
            val session = remember { authGraph.openRegister() }
            DisposableEffect(session) { onDispose { session.close() } }

            AuthLayout(title = "Create account", subtitle = "Ask this server's admin for access.") {
                RegisterForm(
                    state = session.state.collectAsState().value,
                    onSubmit = session.submit,
                    onBack = { pane = LoginPane.SignIn },
                )
            }
        }

        LoginPane.Forgot -> {
            val session = remember { authGraph.openForgotPassword() }
            DisposableEffect(session) { onDispose { session.close() } }

            AuthLayout(
                title = "Reset your password",
                subtitle = "Your server's admin approves this — there is no email to go and check.",
            ) {
                ForgotPasswordPanel(
                    state = session.state.collectAsState().value,
                    onRequestReset = session.requestReset,
                    onCompleteReset = session.completeReset,
                    onCheckStatus = session.checkStatus,
                    onRetryRequest = session.retryRequest,
                    onBackToSignIn = { pane = LoginPane.SignIn },
                )
            }
        }

        LoginPane.Invite -> {
            val session = remember { authGraph.openClaimInvite() }
            DisposableEffect(session) { onDispose { session.close() } }

            // A code that arrived with the link is looked up immediately, so someone who followed
            // an invite lands on "X invited you to Y" rather than on a field asking them to
            // retype what they just clicked. Keyed on the code: re-running this on every
            // recomposition would re-ask the server for the same answer.
            LaunchedEffect(initialInviteCode) {
                initialInviteCode?.let {
                    session.lookUp(it)
                    onInviteConsumed()
                }
            }

            AuthLayout(
                title = "Join a library",
                subtitle = "Redeem the invite you were sent.",
            ) {
                ClaimInvitePanel(
                    state = session.state.collectAsState().value,
                    onCodeEntered = session.lookUp,
                    onClaim = session.claim,
                    onBackToSignIn = { pane = LoginPane.SignIn },
                )
            }
        }

        LoginPane.SignIn -> {
            val session = remember { authGraph.openLogin() }
            DisposableEffect(session) { onDispose { session.close() } }

            AuthLayout(title = "Sign in", subtitle = "Pick up right where you left off in your audiobook library.") {
                LoginForm(
                    state = session.state.collectAsState().value,
                    openRegistration = openRegistration,
                    onSubmit = session.submit,
                    onRegister = { pane = LoginPane.Register },
                    onForgotPassword = { pane = LoginPane.Forgot },
                    onClaimInvite = { pane = LoginPane.Invite },
                )
            }
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

/**
 * Keeps the document's theme in step with the reader's choice and their OS.
 *
 * Both inputs matter and either can change while the page is open: the reader can pick a mode here,
 * and the OS can flip under a reader who chose to follow it. `web.css` has always carried the dark
 * palette; this is the only thing that turns it on.
 */
@Composable
private fun ThemeEffect(observeThemeMode: () -> Flow<ThemeMode>) {
    var mode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var systemDark by remember { mutableStateOf(systemPrefersDark()) }

    LaunchedEffect(Unit) { observeThemeMode().collect { mode = it } }
    DisposableEffect(Unit) {
        val stop = watchSystemTheme { systemDark = it }
        onDispose { stop() }
    }

    // A plain effect keyed on both, so the attribute is rewritten exactly when one of them moves.
    LaunchedEffect(mode, systemDark) { applyTheme(shouldUseDarkTheme(mode, systemDark)) }
}
