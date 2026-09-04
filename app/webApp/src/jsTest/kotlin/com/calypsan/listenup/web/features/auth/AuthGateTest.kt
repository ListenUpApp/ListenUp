package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.web.features.admin.fixedAdmin
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.web.features.admin.fixedLibrarySettings
import com.calypsan.listenup.web.features.devices.fixedDevices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.web.features.settings.fixedSettings
import com.calypsan.listenup.web.features.shelf.fixedShelfDetail
import com.calypsan.listenup.web.features.shelf.fixedShelfEdit
import com.calypsan.listenup.web.features.discover.fixedDiscover
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.web.features.home.fixedHome
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.web.features.seriesdetail.fixedSeriesDetail
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.web.features.notifications.fixedNotificationBell
import com.calypsan.listenup.web.features.notifications.fixedNotifications
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.web.features.notifications.fixedNotificationPrefs
import com.calypsan.listenup.client.presentation.setup.LibrarySetupNavAction
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.web.RECOMPOSE_TIMEOUT_MS
import com.calypsan.listenup.web.features.setup.OpenLibrarySetup
import com.calypsan.listenup.web.features.setup.fixedLibrarySetup
import com.calypsan.listenup.web.features.setup.setupState
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.web.features.profile.fixedProfile
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.contributors.fixedContributors
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback
import com.calypsan.listenup.web.features.search.fixedSearch

/** A signed-in session. The ids are arbitrary — the gate only ever branches on the state's type. */
private fun authenticated() = AuthState.Authenticated(UserId("u1"), SessionId("s1"))

/**
 * Routers created by [mountGate], disposed together after the spec.
 *
 * `Router`'s constructor attaches a global `popstate` listener, so an undisposed one keeps
 * listening for the rest of the browser run — and this spec builds one per test, one of which
 * asserts on `window.location`. `WebAppRootTest` disposes in a `finally` for the same reason.
 */
private val routers = mutableListOf<Router>()

private fun mountGate(
    graph: FakeAuthGraph,
    themeMode: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM),
    inviteCode: String? = null,
    errors: Flow<AppError> = emptyFlow(),
    openLibrarySetup: OpenLibrarySetup = fixedLibrarySetup(setupState(needsSetup = false)),
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    val router = Router().also { routers += it }
    renderComposable(root = host) {
        AuthGate(
            authGraph = graph,
            router = router,
            openLibrarySetup = openLibrarySetup,
            openBookDetail = fixedBookDetail(readyBook()),
            openBookEdit = fixedBookEdit(BookEditUiState()),
            openContributorDetail = fixedContributorDetail(ContributorDetailUiState.Loading),
            openSeriesDetail = fixedSeriesDetail(SeriesDetailUiState.Loading),
            openNotifications = fixedNotifications(NotificationsUiState.Empty),
            openNotificationPrefs = fixedNotificationPrefs(NotificationPrefsUiState.Loading),
            openProfile = fixedProfile(UserProfileUiState.Loading),
            openNotificationBell = fixedNotificationBell(),
            openContributors = fixedContributors(emptyList()),
            openHome = fixedHome(HomeUiState.Loading),
            openDiscover = fixedDiscover(),
            openSettings = fixedSettings(),
            openDevices = fixedDevices(),
            openAdmin = fixedAdmin(),
            openLibrarySettings = fixedLibrarySettings(LibrarySettingsUiState.Loading),
            openShelfDetail = fixedShelfDetail(),
            openShelfEdit = fixedShelfEdit(),
            openLibrary = fakeLibrary(),
            openSearch = fixedSearch(SearchUiState.Idle()),
            openPlayback = fixedPlayback(),
            observeIsAdmin = { flowOf(false) },
            observeCurrentUserId = { flowOf(null) },
            observeThemeMode = { themeMode },
            initialInviteCode = inviteCode,
            observeErrors = { errors },
        )
    }
    return host
}

class AuthGateTest :
    FunSpec({

        afterSpec {
            routers.forEach { it.dispose() }
            routers.clear()
        }

        // A signed-in admin whose server was never pointed at anything reaches a shell with an
        // empty library and no control in it that would help.
        test("a server with no folders shows the wizard instead of the app") {
            val host =
                mountGate(
                    FakeAuthGraph(authenticated()),
                    openLibrarySetup = fixedLibrarySetup(setupState(needsSetup = true)),
                )

            host.querySelector(".lsetup").shouldNotBeNull()
            host.querySelector(".shell") shouldBe null
        }

        test("a server that is already set up goes straight to the app") {
            val host =
                mountGate(
                    FakeAuthGraph(authenticated()),
                    openLibrarySetup = fixedLibrarySetup(setupState(needsSetup = false)),
                )

            host.querySelector(".shell").shouldNotBeNull()
            host.querySelector(".lsetup") shouldBe null
        }

        // Flashing an empty library at precisely the person about to be told why it is empty.
        test("neither branch renders while the status probe is in flight") {
            val host =
                mountGate(
                    FakeAuthGraph(authenticated()),
                    openLibrarySetup = fixedLibrarySetup(setupState(isCheckingStatus = true, needsSetup = false)),
                )

            host.querySelector(".lsetup") shouldBe null
            host.querySelector(".shell") shouldBe null
            host.querySelectorAll(".auth-boot").length shouldBe 1
        }

        // ⛔ The ViewModel does NOT flip `needsSetup` back to false on success — its last act is to
        // start the scan and emit the one-shot. A gate re-reading the state would show the wizard
        // again, over a library that was just configured, forever.
        test("finishing setup opens the app even though the state still says setup is needed") {
            val finished = MutableSharedFlow<LibrarySetupNavAction>(replay = 1)
            finished.tryEmit(LibrarySetupNavAction.Finished)
            val host =
                mountGate(
                    FakeAuthGraph(authenticated()),
                    openLibrarySetup =
                        fixedLibrarySetup(setupState(needsSetup = true), navActions = finished),
                )

            withTimeout(RECOMPOSE_TIMEOUT_MS) {
                while (host.querySelector(".shell") == null) delay(10)
            }
            host.querySelector(".lsetup") shouldBe null
        }

        test("initializing shows the boot surface, never a login form") {
            // Flashing a sign-in form at a user who has a perfectly good stored session is the
            // single most visible way this gate can lie.
            val host = mountGate(FakeAuthGraph(AuthState.Initializing))

            host.querySelectorAll(".auth-boot").length shouldBe 1
            host.querySelectorAll(".f-input").length shouldBe 0
        }

        test("checking the server also shows the boot surface") {
            val host = mountGate(FakeAuthGraph(AuthState.CheckingServer))

            host.querySelectorAll(".auth-boot").length shouldBe 1
        }

        test("an unreachable state is inert rather than fatal") {
            // NeedsServerUrl cannot happen on web — main() seeds the URL from the page origin
            // before mounting. It still must not throw or render an empty page.
            val host = mountGate(FakeAuthGraph(AuthState.NeedsServerUrl))

            host.querySelectorAll(".auth-boot").length shouldBe 1
        }

        test("a server with no users asks for the first admin") {
            val host = mountGate(FakeAuthGraph(AuthState.NeedsSetup))

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "admin"
            host.querySelectorAll("#auth-confirm").length shouldBe 1
        }

        test("needing a login shows sign in") {
            val host = mountGate(FakeAuthGraph(AuthState.NeedsLogin()))

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Sign in"
        }

        test("the create-account link follows the server's registration setting") {
            // Named rather than counted. The sign-in footer also carries recovery and the
            // invite redeem link, both offered whatever the server says about new accounts, so a
            // link COUNT answers a different question than the one this test is asking.
            val closed = mountGate(FakeAuthGraph(AuthState.NeedsLogin(openRegistration = false)))
            val open = mountGate(FakeAuthGraph(AuthState.NeedsLogin(openRegistration = true)))

            closed.textContent.orEmpty() shouldNotContain "Create account"
            open.textContent.orEmpty() shouldContain "Create account"
        }

        test("choosing create account swaps the form without touching the URL") {
            // Register is a sub-state of NeedsLogin, not a route: no /register entry means Back
            // leaves the app rather than unwinding a form.
            val before = window.location.pathname
            val host = mountGate(FakeAuthGraph(AuthState.NeedsLogin(openRegistration = true)))

            host.linkNamed("Create account").click()
            awaitFrame()

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Create"
            window.location.pathname shouldBe before
        }

        test("choosing forgot password swaps the form without touching the URL") {
            // Same rule as register: a sub-state of NeedsLogin, not a route. "I forgot my
            // password" is not a place, and a URL for it would be a second source of truth for a
            // question only AuthState can answer.
            val before = window.location.pathname
            val host = mountGate(FakeAuthGraph(AuthState.NeedsLogin()))

            (host.querySelector(".auth-aside .lnk") as HTMLElement).click()
            awaitFrame()

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Reset"
            window.location.pathname shouldBe before
        }

        test("the reset flow reaches the shared ViewModel with the address typed into it") {
            // The wiring nothing else covers: the panel is proved to call its callback and the
            // ViewModel is proved to open a request, but a session wired to the wrong function
            // would leave both green and the screen inert.
            val graph = FakeAuthGraph(AuthState.NeedsLogin())
            val host = mountGate(graph)

            (host.querySelector(".auth-aside .lnk") as HTMLElement).click()
            awaitFrame()
            val input = host.querySelector("#auth-reset-email") as HTMLInputElement
            input.value = "ada@example.com"
            input.dispatchEvent(Event("input", EventInit(bubbles = true)))
            (host.querySelector("button[type=submit]") as HTMLElement).click()

            graph.resetRequestedFor shouldBe "ada@example.com"
        }

        test("arriving with an invite code opens the claim pane and looks it up") {
            // The whole point of the URL entry: someone who followed an invite link lands on
            // "X invited you to Y", not on a field asking them to retype what they just clicked.
            val graph = FakeAuthGraph(AuthState.NeedsLogin())
            val host = mountGate(graph, inviteCode = "TREEHOUSE-42")
            awaitFrame()

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Join"
            graph.invitesLookedUp shouldBe listOf("TREEHOUSE-42")
        }

        test("an arriving code is looked up once, not on every recomposition") {
            // The lookup is a network call keyed on the code. Unkeyed it would re-fire on any
            // recomposition of the branch and ask the server the same question repeatedly.
            val graph = FakeAuthGraph(AuthState.NeedsLogin())
            mountGate(graph, inviteCode = "TREEHOUSE-42")
            awaitFrame()
            awaitFrame()

            graph.invitesLookedUp shouldBe listOf("TREEHOUSE-42")
        }

        test("with no code the gate opens on sign in, not on the claim pane") {
            val host = mountGate(FakeAuthGraph(AuthState.NeedsLogin()))
            awaitFrame()

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Sign in"
        }

        test("the redeem link opens the claim pane with nothing looked up") {
            // The manual path: told a code rather than sent a link. Nothing to look up until it
            // is typed, so a lookup here would be a call with no code behind it.
            val graph = FakeAuthGraph(AuthState.NeedsLogin())
            val host = mountGate(graph)

            host.linkNamed("Redeem it").click()
            awaitFrame()

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Join"
            graph.invitesLookedUp shouldBe emptyList()
        }

        test("leaving NeedsLogin clears the claim sub-state, and tears its ViewModel down") {
            // The code is one-shot. This caught the real bug: held as a plain parameter, a code
            // redeemed minutes ago re-opened the claim pane every single time the reader came
            // back to sign-in — the branch remembers which pane it is on, but it re-derives the
            // OPENING pane each time it remounts, and the parameter was still sitting there.
            val graph = FakeAuthGraph(AuthState.NeedsLogin())
            val host = mountGate(graph, inviteCode = "TREEHOUSE-42")
            awaitFrame()

            graph.state.value = AuthState.Authenticated(UserId("u1"), SessionId("s1"))
            awaitFrame()
            graph.state.value = AuthState.NeedsLogin()
            awaitFrame()

            graph.closed shouldContain "invite"
            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Sign in"
        }

        test("leaving NeedsLogin clears the register sub-state") {
            // Otherwise signing out later would drop the user straight back onto a registration
            // form they abandoned minutes ago.
            val graph = FakeAuthGraph(AuthState.NeedsLogin(openRegistration = true))
            val host = mountGate(graph)
            host.linkNamed("Create account").click()
            awaitFrame()

            graph.state.value = AuthState.Authenticated(UserId("u1"), SessionId("s1"))
            awaitFrame()
            graph.state.value = AuthState.NeedsLogin(openRegistration = true)
            awaitFrame()

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Sign in"
        }

        test("leaving NeedsLogin clears the reset sub-state, and tears its ViewModel down") {
            // The reset ViewModel holds a status stream AND a poll loop that never stops on its
            // own while awaiting approval. Abandoning it un-closed would leave both running for
            // the life of the tab, on a ticket nobody is watching.
            val graph = FakeAuthGraph(AuthState.NeedsLogin())
            val host = mountGate(graph)
            (host.querySelector(".auth-aside .lnk") as HTMLElement).click()
            awaitFrame()

            graph.state.value = AuthState.Authenticated(UserId("u1"), SessionId("s1"))
            awaitFrame()
            graph.state.value = AuthState.NeedsLogin()
            awaitFrame()

            graph.closed shouldContain "forgot"
            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Sign in"
        }

        test("pending approval shows the waiting room with the registered email") {
            val host = mountGate(FakeAuthGraph(AuthState.PendingApproval(UserId("u1"), "ada@example.com")))

            host.textContent.orEmpty() shouldContain "ada@example.com"
        }

        test("authenticating renders the shell") {
            val host = mountGate(FakeAuthGraph(AuthState.Authenticated(UserId("u1"), SessionId("s1"))))

            host.querySelectorAll(".shell").length shouldBe 1
            host.querySelectorAll(".auth").length shouldBe 0
        }

        test("a lapsed session keeps the shell rather than walling the user out") {
            // Matches AuthNavigation.kt's Authenticated/SessionLapsed branch. The dedicated
            // re-auth affordance is deferred on every platform — see AuthState.SessionLapsed.
            val host = mountGate(FakeAuthGraph(AuthState.SessionLapsed(UserId("u1"))))

            host.querySelectorAll(".shell").length shouldBe 1
        }

        test("the surface wrapper is applied exactly once") {
            // WebAppSurface moved up to the gate; if WebAppRoot still applied its own, the
            // authenticated branch would nest .luw inside .luw and the sheet's scoping would
            // silently double up.
            val host = mountGate(FakeAuthGraph(AuthState.Authenticated(UserId("u1"), SessionId("s1"))))

            host.querySelectorAll(".luw").length shouldBe 1
        }

        test("the gate resolves auth state on mount") {
            val graph = FakeAuthGraph(AuthState.Initializing)
            mountGate(graph)
            // LaunchedEffect is scheduled, not synchronous with the first composition.
            awaitFrame()

            graph.initializeCalls shouldBe 1
        }

        test("entering sign-in re-reads whether registration is open") {
            // The flag is cached in AuthState from whenever it was last read. An admin who turns
            // registration on should not have to wait for a reader to reload the tab before the
            // "Create account" link appears — AuthNavigation.kt:252 refreshes for the same reason.
            val graph = FakeAuthGraph(AuthState.NeedsLogin())
            mountGate(graph)
            awaitFrame()

            graph.refreshOpenRegistrationCalls shouldBe 1
        }

        test("an error emitted anywhere reaches the reader") {
            // The link nothing else covers, and the reason this exists at all: 96 `errorBus.emit`
            // calls across the shared ViewModels fed a bus that NOTHING on web subscribed to, so
            // every failure the shared layer reported was silent in a browser. ToastQueue is
            // proved on its own and the sheet styles `.toast`; only this proves they are joined.
            val errors = MutableSharedFlow<AppError>(extraBufferCapacity = 4)
            val host = mountGate(FakeAuthGraph(AuthState.NeedsLogin()), errors = errors)
            awaitFrame()

            errors.tryEmit(TransportError.NetworkUnavailable())
            awaitFrame()

            (host.querySelector(".toast") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "No internet connection."
        }

        test("errors reach the reader on the signed-out screens too") {
            // Where it matters most: someone who cannot sign in is driven by shared ViewModels
            // that report through the same bus, and a toast layer living inside the shell would
            // drop exactly those.
            val errors = MutableSharedFlow<AppError>(extraBufferCapacity = 4)
            val host =
                mountGate(
                    FakeAuthGraph(AuthState.PendingApproval(UserId("u1"), "ada@example.com")),
                    errors = errors,
                )
            awaitFrame()

            errors.tryEmit(AuthError.RateLimited(retryAfterSeconds = 30))
            awaitFrame()

            (host.querySelector(".toast") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "Try again in 30s."
        }

        test("a closed screen tears its ViewModel down") {
            // Every visited screen would otherwise leave its flows collecting for the life of
            // the tab — the browser has no ViewModelStore owner to do it for us.
            val graph = FakeAuthGraph(AuthState.NeedsLogin())
            mountGate(graph)

            graph.state.value = AuthState.Authenticated(UserId("u1"), SessionId("s1"))
            // Disposal rides the recomposition that swaps the branch out.
            awaitFrame()

            graph.closed shouldBe listOf("login")
        }
        test("the reader's theme reaches the document, from the gate up") {
            // The link nothing else covers. `shouldUseDarkTheme` and `applyTheme` are proved on
            // their own, and the Settings page is proved to report a chosen mode — but a
            // ThemeEffect that was never mounted, or a flow nothing collected, would leave every
            // one of those green while the page stayed stubbornly light.
            document.documentElement?.removeAttribute("data-theme")

            mountGate(FakeAuthGraph(AuthState.NeedsLogin()), themeMode = flowOf(ThemeMode.DARK))
            awaitFrame()

            document.documentElement?.getAttribute("data-theme") shouldBe "dark"
        }

        test("dark applies on the sign-in screen, not only inside the shell") {
            // Deliberately asserted against NeedsLogin: a ThemeEffect placed inside the
            // authenticated branch would pass every other spec and still flash a white sign-in
            // screen at someone who chose dark.
            document.documentElement?.removeAttribute("data-theme")

            val host = mountGate(FakeAuthGraph(AuthState.NeedsLogin()), themeMode = flowOf(ThemeMode.DARK))
            awaitFrame()

            host.textContent.orEmpty() shouldContain "Sign in"
            document.documentElement?.getAttribute("data-theme") shouldBe "dark"
        }

        test("choosing light takes the attribute back off again") {
            document.documentElement?.setAttribute("data-theme", "dark")

            mountGate(FakeAuthGraph(AuthState.NeedsLogin()), themeMode = flowOf(ThemeMode.LIGHT))
            awaitFrame()

            document.documentElement?.hasAttribute("data-theme") shouldBe false
        }

        test("a later change reaches the document too, not just the first value") {
            // A `LaunchedEffect(Unit)` that read one value and stopped would pass the specs above
            // and leave the switcher dead after its first use.
            document.documentElement?.removeAttribute("data-theme")
            val modes = MutableStateFlow(ThemeMode.LIGHT)

            mountGate(FakeAuthGraph(AuthState.NeedsLogin()), themeMode = modes)
            awaitFrame()
            document.documentElement?.hasAttribute("data-theme") shouldBe false

            modes.value = ThemeMode.DARK
            awaitFrame()

            document.documentElement?.getAttribute("data-theme") shouldBe "dark"
        }
    })

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}

/**
 * The footer link with exactly this text.
 *
 * Sign-in's footer now carries three links, so a positional or count-based selector answers a
 * different question than the caller is asking — and does it silently, by picking the wrong one.
 */
private fun HTMLElement.linkNamed(text: String): HTMLElement {
    val links = querySelectorAll(".lnk")
    for (i in 0 until links.length) {
        val link = links.item(i) as? HTMLElement ?: continue
        if (link.textContent?.trim() == text) return link
    }
    error("no link named \"$text\"")
}
