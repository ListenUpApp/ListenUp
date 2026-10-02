package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.admin.fixedRestrictedBooks
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.library.fakeLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The shell publishes the restricted-book session's set to every page: a card deep inside a route
 * finds the lock without its page knowing the feature exists.
 */
class RestrictedBooksWiringTest :
    FunSpec({
        test("the shell's restricted set reaches a library card") {
            val (host, router, composition) =
                mountAt(
                    "/library",
                    openLibrary = fakeLibrary(contractLibrary(books = listOf(contractBook("b1", "Dune"), contractBook("b2", "Ubik")))),
                    openRestrictedBooks = fixedRestrictedBooks(setOf("b1")),
                )
            try {
                host.querySelectorAll(".lib-cover > .lu-lock").length shouldBe 1
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
