package com.calypsan.listenup.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement

/**
 * An address nothing lives at says so, and offers the way home.
 *
 * It used to say "This page is not built yet." under a title borrowed from the nav — honest while
 * pages were still arriving, and wrong once they had: a mistyped or outdated link read as an
 * unfinished app, and left the reader with nowhere to go but the sidebar.
 */
class NotFoundTest :
    FunSpec({

        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
        }

        listOf("/nowhere", "/settings/nonsense", "/admin/nonsense").forEach { path ->
            test("$path is a page that was not found, headed and titled as one") {
                val (host, router, composition) = mountAt(path)

                try {
                    host.querySelector("h1").shouldNotBeNull().textContent?.trim() shouldBe "Page not found"
                    document.title shouldBe "Page not found · ListenUp"
                    host.textContent.orEmpty() shouldNotContain "not built yet"
                } finally {
                    composition.dispose()
                    router.dispose()
                }
            }
        }

        test("the way home is a real link to /, followed in the app") {
            val (host, router, composition) = mountAt("/nowhere")

            try {
                val home = host.querySelector(".empty a").shouldNotBeNull() as HTMLAnchorElement
                home.getAttribute("href") shouldBe "/"
                home.click()
                awaitFrame()

                window.location.pathname shouldBe "/"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
