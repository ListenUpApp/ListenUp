package com.calypsan.listenup.web.nav

import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.Div
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.window
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The URL is the page contract — `?tab=chapters&sel=9,10` must survive a round trip through the
 * codec, and the push/replace split is what keeps the Back button honest: navigation pushes,
 * filter changes replace.
 */
class RouterTest :
    FunSpec({

        // History manipulation leaks between tests unless the entry is restored.
        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
        }

        test("a deep link parses into segments and query") {
            val route = Route.parse("/book/42?tab=chapters&sel=9,10")

            route.segments shouldBe listOf("book", "42")
            route.query shouldBe mapOf("tab" to "chapters", "sel" to "9,10")
        }

        test("the root path is the empty route") {
            val route = Route.parse("/")

            route.segments shouldBe emptyList()
            route.query shouldBe emptyMap()
        }

        test("a route serializes back to the url it was parsed from") {
            val url = "/book/42?tab=chapters&sel=9,10"

            Route.parse(url).toUrl() shouldBe url
        }

        test("commas stay readable in the query string") {
            // The selection contract is literally "?sel=9,10" — an escaped %2C would still work
            // mechanically but the URL is meant to be read and shared by people.
            val url = Route(listOf("book", "42"), mapOf("sel" to "9,10")).toUrl()

            url shouldContain "sel=9,10"
            url shouldNotContain "%2C"
        }

        test("reserved characters round-trip through the query") {
            val route = Route(listOf("search"), mapOf("q" to "war & peace = 100%"))

            Route.parse(route.toUrl()).query shouldBe mapOf("q" to "war & peace = 100%")
        }

        // A lone `%` is not valid percent-encoding, and the address bar accepts anything a person
        // can type or hand-edit into a link. Showing the raw text is wrong in a way the reader can
        // see and fix; throwing out of the boot coroutine is a white page.
        test("a malformed percent escape in a path segment survives as raw text") {
            Route.parse("/book/100%").segments shouldBe listOf("book", "100%")
        }

        test("a malformed percent escape in a query value survives as raw text") {
            Route.parse("/search?q=50%").query shouldBe mapOf("q" to "50%")
        }

        test("a malformed percent escape in a query key survives as raw text") {
            Route.parse("/search?%=1").query shouldBe mapOf("%" to "1")
        }

        // ⛔ Do not assert that history.length GREW here. Chrome caps a tab's history at 50
        // entries and the browser suite shares one tab, so by the time this spec runs the stack
        // is full: a push still pushes, it just drops the oldest entry instead of growing the
        // number, and the assertion fails forever. Push semantics are covered by the back-button
        // tests below instead — verified by sabotage: a `navigate` that replaceStates does not
        // survive this suite.
        test("navigate updates the URL and the current route") {
            val router = Router()

            try {
                router.navigate(Route(listOf("library")))

                window.location.pathname shouldBe "/library"
                router.current.segments shouldBe listOf("library")
            } finally {
                router.dispose()
            }
        }

        test("replace changes the route without adding a history entry") {
            // Filter changes use replace so Back leaves the page, not the filter.
            val router = Router()
            router.navigate(Route(listOf("book", "42"), mapOf("tab" to "chapters")))
            val depth = window.history.length

            try {
                router.replace(Route(listOf("book", "42"), mapOf("tab" to "chapters", "sel" to "9,10")))

                window.location.search shouldContain "sel=9,10"
                window.history.length shouldBe depth
                router.current.query shouldBe mapOf("tab" to "chapters", "sel" to "9,10")
            } finally {
                router.dispose()
            }
        }

        test("the back button restores the previous route") {
            val router = Router()
            val start = Route.parse(window.location.pathname + window.location.search)
            router.navigate(Route(listOf("library")))

            try {
                window.history.back()
                nextPopstate()

                router.current.segments shouldBe start.segments
            } finally {
                router.dispose()
            }
        }

        test("the outgoing route is announced before current moves, on every path that moves it") {
            // The shared-element flight reads the page it is leaving while that page is still laid
            // out, so what this pins is ordering: the hook runs while `current` — and therefore
            // everything composed from it — is still the outgoing page.
            //
            // ⛔ `window.location` is NOT the outgoing page on the Back path: popstate fires after
            // the browser has already moved the URL. `current` is the only outgoing-route reading
            // that holds on all three paths, which is why the hook is defined against it.
            val seen = mutableListOf<List<String>>()
            var observed: Router? = null
            val router = Router(beforeRouteChange = { _ -> observed?.let { seen += it.current.segments } })
            observed = router

            try {
                router.navigate(Route(listOf("library")))
                router.replace(Route(listOf("library"), mapOf("sort" to "title")))
                router.navigate(Route(listOf("book", "42")))
                window.history.back()
                nextPopstate()

                seen.size shouldBe 4
                seen[1] shouldBe listOf("library")
                seen[2] shouldBe listOf("library")
                seen[3] shouldBe listOf("book", "42")
            } finally {
                router.dispose()
            }
        }

        // Unsaved work: a page that holds a draft registers a hold, and every way out asks it first.
        test("navigating while unsaved work is held asks first and goes only once the answer is yes") {
            val router = Router()
            val start = router.current
            var proceed: (() -> Unit)? = null
            val release = router.holdUnsavedWork(heldHold(onAsk = { proceed = it }))

            try {
                router.navigate(Route(listOf("library")))

                router.current.segments shouldBe start.segments
                window.location.pathname shouldBe start.toUrl().substringBefore('?')

                proceed!!.invoke()

                router.current.segments shouldBe listOf("library")
                window.location.pathname shouldBe "/library"
            } finally {
                release()
                router.dispose()
            }
        }

        test("a forced navigation does not ask, and neither does one once the hold is released") {
            val router = Router()
            var asked = 0
            val release = router.holdUnsavedWork(heldHold(onAsk = { asked++ }))

            try {
                router.navigate(Route(listOf("library")), force = true)
                router.current.segments shouldBe listOf("library")

                release()
                router.navigate(Route(listOf("book", "42")))

                router.current.segments shouldBe listOf("book", "42")
                asked shouldBe 0
            } finally {
                router.dispose()
            }
        }

        test("a hold that is not held lets navigation through") {
            val router = Router()
            val release = router.holdUnsavedWork(UnsavedWorkHold(isHeld = { false }, isAsking = { false }, ask = { error("asked") }))

            try {
                router.navigate(Route(listOf("library")))

                router.current.segments shouldBe listOf("library")
            } finally {
                release()
                router.dispose()
            }
        }

        test("the browser Back button while unsaved work is held stays on the page and asks") {
            val router = Router()
            router.navigate(Route(listOf("library")))
            router.navigate(Route(listOf("book", "42", "edit")))
            var proceed: (() -> Unit)? = null
            val release = router.holdUnsavedWork(heldHold(onAsk = { proceed = it }))

            try {
                window.history.back()
                nextPopstate()
                awaitTurn()

                router.current.segments shouldBe listOf("book", "42", "edit")
                window.location.pathname shouldBe "/book/42/edit"
                proceed.shouldNotBeNull()

                proceed!!.invoke()
                nextPopstate()

                router.current.segments shouldBe listOf("library")
                window.location.pathname shouldBe "/library"
            } finally {
                release()
                router.dispose()
            }
        }

        test("Back on a held page, answered Keep, leaves the page's scroll and how it was arrived at alone") {
            // ⛔ A vetoed pop is not a route change. Were the hook to run for it, the page that stays
            // would be recorded as left (its offset saved as though it had been departed), the last
            // change would read POP, and every shared-element flight would wait on an arrival that
            // never comes.
            val frames = ViewportFrames()
            val router = Router(beforeRouteChange = ::captureScrollBeforeRouteChange)
            router.navigate(Route(listOf("library")))
            val editing = Route(listOf("book", "42", "edit"))
            router.navigate(editing)
            var asked = 0
            val release = router.holdUnsavedWork(heldHold(onAsk = { asked++ }))

            try {
                val main =
                    frames
                        .mount(1280, 800) {
                            InShell {
                                ScrollRestoration(router.current)
                                Div(attrs = {
                                    style {
                                        height(6_000.px)
                                        property("flex-shrink", "0")
                                    }
                                })
                            }
                        }.find(".shell-main")
                awaitFrame()
                main.scrollTop = 1_500.0
                val changeBefore = lastRouteChange()
                val savedBefore = savedScrollFor(editing)

                window.history.back()
                nextPopstate()
                awaitTurn()
                awaitFrame()

                // Keep: the question was asked and is never answered with a discard.
                asked shouldBe 1
                router.current.segments shouldBe editing.segments
                window.location.pathname shouldBe "/book/42/edit"
                main.scrollTop shouldBe 1_500.0
                lastRouteChange() shouldBe changeBefore
                changeBefore shouldBe RouteChange.PUSH
                savedScrollFor(editing) shouldBe savedBefore
            } finally {
                release()
                router.dispose()
                frames.disposeAll()
                forgetScrollMemory()
            }
        }

        test("the hook is told how the route is changing") {
            // Back restores a place; a link starts a new one; a filter keeps the one it is in. The
            // hook is where that difference is first known, so it is told — not left to guess.
            val changes = mutableListOf<RouteChange>()
            val router = Router(beforeRouteChange = { changes += it })

            try {
                router.navigate(Route(listOf("library")))
                router.replace(Route(listOf("library"), mapOf("sort" to "title")))
                window.history.back()
                nextPopstate()

                changes shouldBe listOf(RouteChange.PUSH, RouteChange.REPLACE, RouteChange.POP)
            } finally {
                router.dispose()
            }
        }

        test("a disposed router stops announcing the outgoing route") {
            var announced = 0
            val router = Router(beforeRouteChange = { announced++ })
            router.navigate(Route(listOf("library")))
            val atDispose = announced
            router.dispose()

            window.history.back()
            nextPopstate()

            announced shouldBe atDispose
        }

        test("a disposed router stops following history") {
            val router = Router()
            router.navigate(Route(listOf("library")))
            router.dispose()

            window.history.back()
            nextPopstate()

            router.current.segments shouldBe listOf("library")
        }
    })

private fun heldHold(onAsk: (() -> Unit) -> Unit) = UnsavedWorkHold(isHeld = { true }, isAsking = { false }, ask = onAsk)

/** Lets the router's own reaction to a popstate (a corrective push) settle. */
private suspend fun awaitTurn() {
    suspendCoroutine { continuation -> window.setTimeout({ continuation.resume(Unit) }, 0) }
}

/** Resolves after the next popstate event — registered listeners (the router's) run first. */
private suspend fun nextPopstate() {
    suspendCoroutine { continuation ->
        val listener: (Event) -> Unit = { _ -> continuation.resume(Unit) }
        window.addEventListener("popstate", listener, js("({ once: true })"))
    }
}
