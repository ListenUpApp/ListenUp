package com.calypsan.listenup.web.nav

import io.kotest.core.spec.style.FunSpec
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

        test("navigate pushes a history entry and updates the current route") {
            val router = Router()
            val before = window.history.length

            try {
                router.navigate(Route(listOf("library")))

                window.location.pathname shouldBe "/library"
                window.history.length shouldBe before + 1
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

        test("a disposed router stops following history") {
            val router = Router()
            router.navigate(Route(listOf("library")))
            router.dispose()

            window.history.back()
            nextPopstate()

            router.current.segments shouldBe listOf("library")
        }
    })

/** Resolves after the next popstate event — registered listeners (the router's) run first. */
private suspend fun nextPopstate() {
    suspendCoroutine { continuation ->
        val listener: (Event) -> Unit = { _ -> continuation.resume(Unit) }
        window.addEventListener("popstate", listener, js("({ once: true })"))
    }
}
