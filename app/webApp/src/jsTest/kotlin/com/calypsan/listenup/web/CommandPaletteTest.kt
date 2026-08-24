package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.search.bookHit
import com.calypsan.listenup.web.features.search.contributorHit
import com.calypsan.listenup.web.features.search.seriesHit
import com.calypsan.listenup.web.features.search.searchResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/** Poll interval while waiting for a navigation to reach `window.location`. */
private const val NAV_POLL_MS = 10L

/**
 * The ⌘K / Ctrl+K / `/` command palette: opening, closing, arrow navigation, hit activation, the
 * "no URL change until you commit" rule, and the accessibility contract (focus moves in, is
 * trapped, and returns on close).
 *
 * Mounts the real [WebAppRoot] behind a real [com.calypsan.listenup.web.nav.Router] via [mountAt]
 * — the palette's shortcut is a single `window`-level `keydown` listener owned by
 * `CommandPaletteHost`, which only exists once the shell is mounted. Rendering
 * [com.calypsan.listenup.web.features.search.CommandPalette] alone (as `ClassContractTest` does,
 * for the render contract) can't exercise any of that wiring.
 *
 * Every test disposes its `composition` as well as its `router` — see [mountAt]'s own KDoc for why
 * that stopped being optional the moment this palette existed.
 */
class CommandPaletteTest :
    FunSpec({

        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
            (document.activeElement as? HTMLElement)?.blur()
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
        }

        fun press(
            key: String,
            metaKey: Boolean = false,
            ctrlKey: Boolean = false,
            shiftKey: Boolean = false,
        ) {
            window.dispatchEvent(
                KeyboardEvent(
                    "keydown",
                    KeyboardEventInit(
                        key = key,
                        metaKey = metaKey,
                        ctrlKey = ctrlKey,
                        shiftKey = shiftKey,
                        bubbles = true,
                        cancelable = true,
                    ),
                ),
            )
        }

        fun type(
            host: HTMLElement,
            text: String,
        ) {
            val input = host.querySelector(".cmdk-panel .f-input") as HTMLInputElement
            input.value = text
            input.dispatchEvent(Event("input", EventInit(bubbles = true)))
        }

        fun highlightedRowName(host: HTMLElement): String? {
            val nameNode = host.querySelector(".search-row.is-highlighted .search-name") as? HTMLElement
            return nameNode?.textContent
        }

        test("Cmd+K opens the command palette") {
            val (host, router, composition) = mountAt("/")

            try {
                host.querySelector(".cmdk-panel") shouldBe null

                press("k", metaKey = true)
                awaitFrame()

                host.querySelector(".cmdk-panel") shouldNotBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Ctrl+K opens the command palette") {
            val (host, router, composition) = mountAt("/")

            try {
                press("k", ctrlKey = true)
                awaitFrame()

                host.querySelector(".cmdk-panel") shouldNotBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Esc closes the command palette") {
            val (host, router, composition) = mountAt("/")

            try {
                press("k", metaKey = true)
                awaitFrame()
                host.querySelector(".cmdk-panel") shouldNotBe null

                press("Escape")
                awaitFrame()

                host.querySelector(".cmdk-panel") shouldBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/ opens the palette from the page body") {
            val (host, router, composition) = mountAt("/")

            try {
                press("/")
                awaitFrame()

                host.querySelector(".cmdk-panel") shouldNotBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("/ does not open the palette while typing in a text input") {
            val (host, router, composition) = mountAt("/search")

            try {
                val input = host.querySelector(".f-input") as HTMLInputElement
                input.focus()

                press("/")
                awaitFrame()

                host.querySelector(".cmdk-panel") shouldBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("arrow keys move the highlight through openable hits, wrapping at both ends") {
            val result =
                searchResult(
                    query = "abc",
                    hits = listOf(bookHit("b1", "Alpha"), bookHit("b2", "Bravo"), bookHit("b3", "Charlie")),
                )
            val (host, router, composition) = mountAt("/", openSearch = hitNavigatingSearch(result))

            try {
                press("k", metaKey = true)
                awaitFrame()

                highlightedRowName(host) shouldBe "Alpha"

                press("ArrowDown")
                awaitFrame()
                highlightedRowName(host) shouldBe "Bravo"

                press("ArrowDown")
                awaitFrame()
                highlightedRowName(host) shouldBe "Charlie"

                // Wraps past the last row back to the first.
                press("ArrowDown")
                awaitFrame()
                highlightedRowName(host) shouldBe "Alpha"

                // Wraps the other way too.
                press("ArrowUp")
                awaitFrame()
                highlightedRowName(host) shouldBe "Charlie"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Enter opens the highlighted BOOK hit, navigating to /book/{id}") {
            val result =
                searchResult(query = "dune", hits = listOf(bookHit("b1", "Dune"), contributorHit("c1", "Frank Herbert")))
            val (host, router, composition) = mountAt("/", openSearch = hitNavigatingSearch(result))

            try {
                press("k", metaKey = true)
                awaitFrame()
                highlightedRowName(host) shouldBe "Dune"

                press("Enter")

                // The nav action rides a Channel — the router.navigate() call happens on the next
                // resumption of the collecting coroutine, not synchronously with the keypress.
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (window.location.pathname == "/") delay(NAV_POLL_MS)
                }
                window.location.pathname shouldBe "/book/b1"
                // The URL flips synchronously inside navigate(); the panel's REMOVAL is a
                // recomposition a frame behind it. Asserting straight off the URL wait checks the
                // DOM one frame early — green here, intermittently red on a two-core CI runner.
                awaitGone(host, ".cmdk-panel")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a hit type with no destination is never reachable by Enter") {
            // SERIES has no route at all. (CONTRIBUTOR sat here until /contributor/{id} landed
            // and made people openable — the palette must never offer a hit it cannot open.)
            val result = searchResult(query = "dune", hits = listOf(seriesHit("s1", "Dune")))
            val (host, router, composition) = mountAt("/", openSearch = hitNavigatingSearch(result))

            try {
                press("k", metaKey = true)
                awaitFrame()
                host.querySelector(".search-row.is-highlighted") shouldBe null

                press("Enter")
                awaitFrame()

                window.location.pathname shouldBe "/"
                host.querySelector(".cmdk-panel") shouldNotBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Shift+Enter commits the current query to /search?q=…") {
            val (host, router, composition) = mountAt("/", openSearch = reactiveSearch())

            try {
                press("k", metaKey = true)
                awaitFrame()
                type(host, "dune")
                awaitFrame()

                press("Enter", shiftKey = true)
                awaitFrame()

                window.location.pathname shouldBe "/search"
                window.location.search shouldBe "?q=dune"
                // Same one-frame assumption as above, even though this navigate is synchronous.
                awaitGone(host, ".cmdk-panel")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("opening, typing and arrowing do not change the URL") {
            val result =
                searchResult(query = "dune", hits = listOf(bookHit("b1", "Alpha"), bookHit("b2", "Bravo")))
            val (host, router, composition) = mountAt("/library", openSearch = hitNavigatingSearch(result))

            try {
                val urlBefore = window.location.pathname + window.location.search

                press("k", metaKey = true)
                awaitFrame()
                type(host, "dune")
                awaitFrame()
                press("ArrowDown")
                awaitFrame()

                window.location.pathname + window.location.search shouldBe urlBefore
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("focus moves into the palette on open and returns to the invoker on close") {
            // The invoker is the /search page's own field — a genuinely focusable control (an
            // actual reader might well have been mid-search when they reached for the shortcut),
            // and distinct by selector from the palette's own field so the two can never be
            // confused for one another.
            val (host, router, composition) = mountAt("/search")

            try {
                val pageInput = host.querySelector(".search-page .f-input") as HTMLInputElement
                pageInput.focus()
                document.activeElement shouldBe pageInput

                press("k", metaKey = true)
                awaitFrame()

                val paletteInput = host.querySelector(".cmdk-panel .f-input") as HTMLInputElement
                document.activeElement shouldBe paletteInput

                press("Escape")
                awaitFrame()

                document.activeElement shouldBe pageInput
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Tab does not move focus out of the palette while it is open") {
            val (host, router, composition) = mountAt("/")

            try {
                press("k", metaKey = true)
                awaitFrame()
                val paletteInput = host.querySelector(".cmdk-panel .f-input") as HTMLInputElement
                document.activeElement shouldBe paletteInput

                press("Tab")
                awaitFrame()

                document.activeElement shouldBe paletteInput
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
