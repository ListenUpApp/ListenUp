package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.web.features.admin.OpenAdmin
import com.calypsan.listenup.web.features.devices.OpenDevices
import com.calypsan.listenup.web.features.hardcover.OpenBookHardcover
import com.calypsan.listenup.web.features.hardcover.OpenHardcover
import com.calypsan.listenup.web.features.hardcover.OpenHardcoverMatch
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
import com.calypsan.listenup.web.features.chaptereditor.OpenChapterEditor
import com.calypsan.listenup.web.features.contributormetadata.OpenContributorMetadata
import com.calypsan.listenup.web.features.books.OpenMultiSelect
import com.calypsan.listenup.web.features.bulkedit.OpenBulkEdit
import com.calypsan.listenup.web.features.browse.OpenBrowseFacet
import com.calypsan.listenup.web.features.browse.OpenGenreDestination
import com.calypsan.listenup.web.features.readers.OpenBookReaders
import com.calypsan.listenup.web.features.ratings.OpenBookRatings
import com.calypsan.listenup.web.features.sync.OpenDeadLetters
import com.calypsan.listenup.web.features.search.OpenSeeAll
import com.calypsan.listenup.web.features.metadata.OpenMetadata
import com.calypsan.listenup.web.features.contributordetail.OpenContributorBooks
import com.calypsan.listenup.web.features.licences.OpenLicences
import com.calypsan.listenup.web.features.contributordetail.OpenContributorDetail
import com.calypsan.listenup.web.features.contributoredit.OpenContributorEdit
import com.calypsan.listenup.web.features.notifications.OpenNotificationBell
import com.calypsan.listenup.web.features.notifications.OpenNotificationPrefs
import com.calypsan.listenup.web.features.notifications.OpenNotifications
import com.calypsan.listenup.web.features.setup.LibrarySetupPage
import com.calypsan.listenup.web.features.setup.OpenLibrarySetup
import com.calypsan.listenup.web.features.profile.OpenEditProfile
import com.calypsan.listenup.web.features.profile.OpenProfile
import com.calypsan.listenup.web.features.seriesdetail.OpenSeriesDetail
import com.calypsan.listenup.web.features.seriesedit.OpenSeriesEdit
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
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.features.admin.AdminSessions

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
 *
 * [notice] is a banner about the whole app, whichever branch shows — the degraded-storage notice.
 * It renders inside the surface, above the branch, so beside the shell it takes a share of the one
 * viewport rather than being added on top of it and scrolling the document.
 */
@Composable
fun AuthGate(
    authGraph: AuthGraph,
    router: Router,
    openBookDetail: OpenBookDetail,
    openBookEdit: OpenBookEdit,
    openChapterEditor: OpenChapterEditor,
    openMetadata: OpenMetadata,
    openContributorDetail: OpenContributorDetail,
    openContributorBooks: OpenContributorBooks,
    openContributorEdit: OpenContributorEdit,
    openContributorMetadata: OpenContributorMetadata,
    openSeriesDetail: OpenSeriesDetail,
    openSeriesEdit: OpenSeriesEdit,
    openNotifications: OpenNotifications,
    openNotificationPrefs: OpenNotificationPrefs,
    openLicences: OpenLicences,
    openLibrarySetup: OpenLibrarySetup,
    openConnectionHealth: OpenConnectionHealth,
    openProfile: OpenProfile,
    openEditProfile: OpenEditProfile,
    openNotificationBell: OpenNotificationBell,
    openLibrary: OpenLibrary,
    openHome: OpenHome,
    openDiscover: OpenDiscover,
    openSettings: OpenSettings,
    openDevices: OpenDevices,
    openHardcover: OpenHardcover,
    openAdmin: OpenAdmin,
    admin: AdminSessions,
    openShelfDetail: OpenShelfDetail,
    openShelfEdit: OpenShelfEdit,
    openSearch: OpenSearch,
    openMultiSelect: OpenMultiSelect,
    openBulkEdit: OpenBulkEdit,
    openBrowseFacet: OpenBrowseFacet,
    openGenreDestination: OpenGenreDestination,
    openBookReaders: OpenBookReaders,
    openBookRatings: OpenBookRatings,
    openHardcoverMatch: OpenHardcoverMatch,
    openBookHardcover: OpenBookHardcover,
    openSeeAll: OpenSeeAll,
    openDeadLetters: OpenDeadLetters,
    openPlayback: OpenPlayback,
    observeIsAdmin: () -> Flow<Boolean>,
    observeCurrentUserId: () -> Flow<String?>,
    observeThemeMode: () -> Flow<ThemeMode>,
    initialInviteCode: String? = null,
    observeErrors: () -> Flow<AppError>,
    notice: @Composable () -> Unit = {},
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
        notice()
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

            // ⛔ SessionLapsed rides with Authenticated because the shell still works — the library
            // is in OPFS and reads, and an already-loaded book keeps playing. What it does NOT get
            // to do is stay silent: this branch used to render the ordinary signed-in shell with no
            // banner and no way back, so a reader whose refresh token died just watched requests
            // fail. (The comment that stood here claimed the affordance was "deferred on every
            // platform". It was not: Android ships a non-dismissible ConnectionHealthBanner and iOS
            // a SessionLapsedBanner with a re-auth sheet. Web was alone in having nothing.)
            is AuthState.Authenticated,
            is AuthState.SessionLapsed,
            -> {
                // ⛔ The projection decides, not `state`. `ConnectionHealthStore` derives its
                // SessionExpired from this very flow, so reading both would show two banners for
                // one expired session — and the projection additionally carries the version-
                // mismatch hint, which nothing on web could surface before.
                val health = remember { openConnectionHealth() }
                DisposableEffect(health) { onDispose { health.close() } }
                ConnectionHealthBanner(
                    state = health.state.collectAsState().value,
                    authGraph = authGraph,
                    onDismissOutdated = health.onDismiss,
                )
                LibrarySetupGate(openLibrarySetup, sessionLapsed = state is AuthState.SessionLapsed) {
                    WebAppRoot(
                        router = router,
                        openBookDetail = openBookDetail,
                        openBookEdit = openBookEdit,
                        openChapterEditor = openChapterEditor,
                        openMetadata = openMetadata,
                        openContributorDetail = openContributorDetail,
                        openContributorBooks = openContributorBooks,
                        openContributorEdit = openContributorEdit,
                        openContributorMetadata = openContributorMetadata,
                        openSeriesDetail = openSeriesDetail,
                        openSeriesEdit = openSeriesEdit,
                        openNotifications = openNotifications,
                        openNotificationPrefs = openNotificationPrefs,
                        openLicences = openLicences,
                        openProfile = openProfile,
                        openEditProfile = openEditProfile,
                        openNotificationBell = openNotificationBell,
                        openLibrary = openLibrary,
                        openHome = openHome,
                        openDiscover = openDiscover,
                        openSettings = openSettings,
                        openDevices = openDevices,
                        openHardcover = openHardcover,
                        openAdmin = openAdmin,
                        admin = admin,
                        openShelfDetail = openShelfDetail,
                        openShelfEdit = openShelfEdit,
                        openSearch = openSearch,
                        openMultiSelect = openMultiSelect,
                        openBulkEdit = openBulkEdit,
                        openBrowseFacet = openBrowseFacet,
                        openGenreDestination = openGenreDestination,
                        openBookReaders = openBookReaders,
                        openBookRatings = openBookRatings,
                        openHardcoverMatch = openHardcoverMatch,
                        openBookHardcover = openBookHardcover,
                        openSeeAll = openSeeAll,
                        openDeadLetters = openDeadLetters,
                        // A confirmed bulk action is exactly the kind of thing a toast is for:
                        // the change is real, it happened off-screen, and the number is the
                        // part the reader cannot check for themselves.
                        onToast = { message -> toasts.show(message, ToastTone.Notice) },
                        onActionToast = { text, tone, action -> toasts.show(text, tone, action) },
                        onSignOut = { scope.launch { authGraph.signOut() } },
                        openPlayback = openPlayback,
                        observeIsAdmin = observeIsAdmin,
                        observeCurrentUserId = observeCurrentUserId,
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
 *
 * ## A probe that failed
 *
 * The ViewModel reports one as `error` with `needsSetup` still false — which used to read here as
 * "nothing to set up", dropping a fresh admin into an empty shell with a toast that vanished and no
 * way to ask again. The rule now is Android's `AppStartupViewModel.resolveOfflineOrFail`, so the
 * two clients answer the same failure the same way:
 *  - **a library already in this browser** opens the app. Offline-first: a server that is down at
 *    sign-in must not take away a mirror that reads perfectly well.
 *  - **a lapsed session** opens the app too. A retry would fail against the same dead credentials,
 *    and the wall would paint over the sign-in banner — the only way back in.
 *  - **otherwise** a retry panel, which re-runs the probe.
 * Until the mirror has answered, the boot surface holds — the same reason as the probe itself.
 */
@Composable
private fun LibrarySetupGate(
    openLibrarySetup: OpenLibrarySetup,
    sessionLapsed: Boolean,
    content: @Composable () -> Unit,
) {
    val session = remember { openLibrarySetup() }
    DisposableEffect(session) { onDispose { session.close() } }
    val state by session.state.collectAsState()

    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        session.navActions.collect { finished = true }
    }

    val checkFailed = state.error != null && !state.needsSetup && !state.isCheckingStatus
    // Null until the mirror answers. Asked only when the probe has failed — the common path never
    // touches it.
    var hasLocalLibrary by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(checkFailed) {
        if (checkFailed && hasLocalLibrary == null) hasLocalLibrary = session.hasLocalLibrary()
    }

    when {
        finished || (!state.needsSetup && !state.isCheckingStatus && !checkFailed) -> {
            content()
        }

        checkFailed && (sessionLapsed || hasLocalLibrary == true) -> {
            content()
        }

        checkFailed && hasLocalLibrary == false -> {
            SetupCheckFailed(onRetry = session.onCheckStatus)
        }

        state.isCheckingStatus || checkFailed -> {
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
                onSelectFolder = session.onSelectFolder,
                onClearSelection = session.onClearSelection,
            )
        }
    }
}

/**
 * The server could not say whether setup is needed, and nothing local can stand in for it.
 *
 * Android's `SetupCheckFailedScreen`, in the sign-in screens' layout: an honest failure with the
 * one move that can fix it, rather than an empty app that looks like a library with no books.
 */
@Composable
private fun SetupCheckFailed(onRetry: () -> Unit) {
    AuthLayout(
        title = "Couldn't check library setup",
        subtitle = "We couldn't reach your server to check your library setup. Check your connection and try again.",
    ) {
        Button(
            kind = ButtonKind.Primary,
            fill = true,
            onClick = onRetry,
            attrs = { classes("setup-check-retry") },
        ) { Text("Try again") }
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
 * and the OS can flip under a reader who chose to follow it. `css/00-base.css` carries the dark
 * palette; this is the only thing that turns it on.
 */
@Composable
private fun ThemeEffect(observeThemeMode: () -> Flow<ThemeMode>) {
    // Null until the reader's stored mode arrives. `index.html` has already painted that mode
    // before the bundle loaded, so guessing SYSTEM here would overwrite the right theme with a
    // wrong one for a frame — the flash the pre-paint seed exists to prevent.
    var mode by remember { mutableStateOf<ThemeMode?>(null) }
    var systemDark by remember { mutableStateOf(systemPrefersDark()) }

    LaunchedEffect(Unit) { observeThemeMode().collect { mode = it } }
    DisposableEffect(Unit) {
        val stop = watchSystemTheme { systemDark = it }
        onDispose { stop() }
    }

    // A plain effect keyed on both, so the attribute is rewritten exactly when one of them moves.
    LaunchedEffect(mode, systemDark) { mode?.let { applyTheme(shouldUseDarkTheme(it, systemDark)) } }
}
