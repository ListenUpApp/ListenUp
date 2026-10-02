package com.calypsan.listenup.web

import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookdetail.readyBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The Visibility panel's three ways out, wired through the real book route: Show to all members
 * reaches the session's restore, Add to a collection opens the session's collection picker, and a
 * collection's name opens that collection's admin page.
 */
class BookDetailVisibilityRouteTest :
    FunSpec({
        fun button(
            host: HTMLElement,
            text: String,
        ) = host
            .querySelectorAll("button")
            .asList()
            .map { it as HTMLButtonElement }
            .single { it.textContent?.trim() == text }

        test("a stranded book's two fixes reach the session") {
            var restores = 0
            var pickers = 0
            val (host, router, composition) =
                mountAt(
                    "/book/42",
                    openBookDetail =
                        fixedBookDetail(
                            readyBook().copy(isAdmin = true, visibility = BookVisibility.Stranded),
                            onRestoreToAllBooks = { restores++ },
                            onShowCollectionPicker = { pickers++ },
                        ),
                )
            try {
                button(host, "Show to all members").click()
                button(host, "Add to a collection").click()
                restores shouldBe 1
                pickers shouldBe 1
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a collection's name opens its admin page") {
            val restricted =
                BookVisibility.Restricted(listOf(CollectionRef("c9", "Sci-Fi Club")), HiddenFrom.Members(listOf("Alice")))
            val (host, router, composition) =
                mountAt(
                    "/book/42",
                    openBookDetail = fixedBookDetail(readyBook().copy(isAdmin = true, visibility = restricted)),
                )
            try {
                (host.querySelector(".bd-vis .pill") as HTMLElement).click()
                window.location.pathname shouldBe "/admin/collections/c9"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
