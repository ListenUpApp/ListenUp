package com.calypsan.listenup.web.features.shelf

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.presentation.shelf.MyShelvesUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.features.home.shelf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private fun HTMLElement.buttonsLabelled(label: String): List<HTMLElement> =
    querySelectorAll("button").asList().filterIsInstance<HTMLElement>().filter { it.textContent?.trim() == label }

/** `/shelves` (spec §3.2 H24): every shelf the reader owns, as Home's cards, with New shelf. */
class MyShelvesPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun page(
            state: MyShelvesUiState,
            onOpenShelf: (String) -> Unit = {},
            onCreateShelf: () -> Unit = {},
        ): HTMLElement = mounts.mount { MyShelvesPage(state, onOpenShelf = onOpenShelf, onCreateShelf = onCreateShelf) }

        test("the page is headed My shelves and lists every shelf as Home's card") {
            val host = page(MyShelvesUiState.Ready(listOf(shelf("Winter"), shelf("Mood"))))
            (host.querySelector("h1") as HTMLElement).textContent shouldBe "My shelves"
            host.querySelectorAll(".shelves-page .home-shelf").length shouldBe 2
        }

        test("New shelf is offered, and a shelf opens itself") {
            var created = false
            var opened: String? = null
            val host =
                page(
                    MyShelvesUiState.Ready(listOf(shelf("Winter"), shelf("Mood"))),
                    onOpenShelf = { opened = it },
                    onCreateShelf = { created = true },
                )
            host.buttonsLabelled("New shelf").single().click()
            created shouldBe true
            (host.querySelectorAll(".home-shelf").item(1) as HTMLElement).click()
            opened shouldBe "shelf-Mood"
        }

        test("no shelves is the invitation, with New shelf still there") {
            val host = page(MyShelvesUiState.Ready(emptyList()))
            host.textContent.orEmpty() shouldContain
                "A shelf is your own list of audiobooks — a series, a mood, a plan for the winter."
            host.buttonsLabelled("New shelf").size shouldBe 1
        }

        test("loading never claims there are no shelves") {
            val host = page(MyShelvesUiState.Loading)
            host.textContent.orEmpty() shouldNotContain "A shelf is your own list"
            host.querySelectorAll(".skel").length shouldBe 2
        }

        test("a failed read says so") {
            page(MyShelvesUiState.Error(UnexpectedClientError())).textContent.orEmpty() shouldContain
                "Couldn't load your shelves."
        }
    })
