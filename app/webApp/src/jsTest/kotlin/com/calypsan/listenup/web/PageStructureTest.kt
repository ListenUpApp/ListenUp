package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.home.fixedHome
import com.calypsan.listenup.web.features.home.readyHome
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.library.fakeLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.flowOf
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The outline a screen reader navigates by: one H1 per page naming it, headings that never skip a
 * level, named navigation landmarks, and a way past the sidebar.
 *
 * Library, Search, Contributors and the Series list all titled themselves with an H3, and the shared
 * `Panel` jumped from a page's H1 straight to H3 — so "next heading" and "list headings" described a
 * document that did not match the one on screen.
 */
class PageStructureTest :
    FunSpec({
        var originalUrl = ""

        beforeTest { originalUrl = window.location.pathname + window.location.search }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
            (document.activeElement as? HTMLElement)?.blur()
        }

        listOf(
            "/",
            "/library",
            "/library/contributors",
            "/library/series",
            "/search",
            "/discover",
            "/notifications",
            "/settings",
            "/admin",
        ).forEach { path ->
            test("$path has exactly one H1, and no heading skips a level") {
                val (host, router, composition) =
                    mountAt(
                        path,
                        isAdmin = flowOf(true),
                        openHome = fixedHome(readyHome()),
                        openLibrary = fakeLibrary(contractLibrary()),
                    )

                try {
                    awaitFrame()
                    val main = host.querySelector(".shell-main") as HTMLElement

                    main.querySelectorAll("h1").length shouldBe 1
                    skippedLevels(main).shouldBeEmpty()
                } finally {
                    composition.dispose()
                    router.dispose()
                }
            }
        }

        test("the two navigation landmarks are named, so they can be told apart") {
            val (host, router, composition) = mountAt("/")

            try {
                host.querySelectorAll("nav").asList().map { (it as HTMLElement).getAttribute("aria-label") } shouldBe
                    listOf("Main", "Account")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the first Tab stop skips to the content, and following it lands focus there") {
            val (host, router, composition) = mountAt("/settings")

            try {
                val firstFocusable = host.querySelector("a[href], button, input, [tabindex='0']") as HTMLElement
                val skip = host.querySelector(".skip-link") as HTMLAnchorElement

                firstFocusable shouldBe skip
                skip.textContent shouldBe "Skip to content"
                val main = host.querySelector("main") as HTMLElement
                skip.getAttribute("href") shouldBe "#${main.id}"

                skip.click()

                document.activeElement shouldBe main
                // Handled in script: the router owns the URL, so no fragment lands in it.
                window.location.hash shouldBe ""
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })

/** Each place a heading goes more than one level deeper than the one before it, e.g. "h1 → h3". */
private fun skippedLevels(root: HTMLElement): List<String> {
    val levels = root.querySelectorAll("h1, h2, h3, h4, h5, h6").asList().map { it.nodeName.drop(1).toInt() }
    return levels.zipWithNext().filter { (from, to) -> to > from + 1 }.map { (from, to) -> "h$from → h$to" }
}
