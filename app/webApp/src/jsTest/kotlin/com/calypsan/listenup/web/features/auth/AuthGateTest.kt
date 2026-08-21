package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback

/**
 * Routers created by [mountGate], disposed together after the spec.
 *
 * `Router`'s constructor attaches a global `popstate` listener, so an undisposed one keeps
 * listening for the rest of the browser run — and this spec builds one per test, one of which
 * asserts on `window.location`. `WebAppRootTest` disposes in a `finally` for the same reason.
 */
private val routers = mutableListOf<Router>()

private fun mountGate(graph: FakeAuthGraph): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    val router = Router().also { routers += it }
    renderComposable(root = host) {
        AuthGate(
            authGraph = graph,
            router = router,
            openBookDetail = fixedBookDetail(readyBook()),
            openLibrary = fakeLibrary(),
            openPlayback = fixedPlayback(),
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
            val closed = mountGate(FakeAuthGraph(AuthState.NeedsLogin(openRegistration = false)))
            val open = mountGate(FakeAuthGraph(AuthState.NeedsLogin(openRegistration = true)))

            closed.querySelectorAll(".lnk").length shouldBe 0
            open.querySelectorAll(".lnk").length shouldBe 1
        }

        test("choosing create account swaps the form without touching the URL") {
            // Register is a sub-state of NeedsLogin, not a route: no /register entry means Back
            // leaves the app rather than unwinding a form.
            val before = window.location.pathname
            val host = mountGate(FakeAuthGraph(AuthState.NeedsLogin(openRegistration = true)))

            (host.querySelector(".lnk") as HTMLElement).click()
            awaitFrame()

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Create"
            window.location.pathname shouldBe before
        }

        test("leaving NeedsLogin clears the register sub-state") {
            // Otherwise signing out later would drop the user straight back onto a registration
            // form they abandoned minutes ago.
            val graph = FakeAuthGraph(AuthState.NeedsLogin(openRegistration = true))
            val host = mountGate(graph)
            (host.querySelector(".lnk") as HTMLElement).click()
            awaitFrame()

            graph.state.value = AuthState.Authenticated(UserId("u1"), SessionId("s1"))
            awaitFrame()
            graph.state.value = AuthState.NeedsLogin(openRegistration = true)
            awaitFrame()

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
    })

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}
