package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.BookContributor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/**
 * The byline used to name a book's people without leading anywhere. These pin the two halves of
 * that promise now kept: each name is its own activatable control carrying its own id — not the
 * first contributor's, not a name string a click handler has to re-parse — and the sentence still
 * reads exactly as before when a half (or both) is missing.
 */
class BookDetailBylineTest :
    FunSpec({

        fun render(
            authors: List<BookContributor>,
            narrators: List<BookContributor>,
            onOpenContributor: (String) -> Unit = {},
        ): HTMLElement {
            val root = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            renderComposable(root = root) {
                BookDetailPage(
                    state = readyBook(authors = authors, narrators = narrators),
                    tab = "overview",
                    onSelectTab = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onOpenContributor = onOpenContributor,
                )
            }
            return root
        }

        fun EventTarget.press(key: String) {
            dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
        }

        test("clicking the second author reports that author's id, not the first's") {
            val opened = mutableListOf<String>()
            val root =
                render(
                    authors =
                        listOf(
                            BookContributor(id = "a1", name = "Author One"),
                            BookContributor(id = "a2", name = "Author Two"),
                        ),
                    narrators = emptyList(),
                    onOpenContributor = { opened += it },
                )

            (root.querySelectorAll(".bd-by-name").item(1) as HTMLElement).click()

            opened shouldBe listOf("a2")
        }

        test("clicking a narrator reports the narrator's id, distinct from the author's") {
            val opened = mutableListOf<String>()
            val root =
                render(
                    authors = listOf(BookContributor(id = "a1", name = "Author One")),
                    narrators = listOf(BookContributor(id = "n1", name = "Narrator One")),
                    onOpenContributor = { opened += it },
                )

            (root.querySelectorAll(".bd-by-name").item(1) as HTMLElement).click()

            opened shouldBe listOf("n1")
        }

        test("authors only: no stray middot and no empty read-by") {
            val root = render(authors = listOf(BookContributor(id = "a1", name = "Solo Author")), narrators = emptyList())

            (root.querySelector(".bd-by") as HTMLElement).textContent shouldBe "Solo Author"
        }

        test("narrators only: reads 'read by NAME' with no leading middot") {
            val root =
                render(authors = emptyList(), narrators = listOf(BookContributor(id = "n1", name = "Solo Narrator")))

            (root.querySelector(".bd-by") as HTMLElement).textContent shouldBe "read by Solo Narrator"
        }

        test("neither authors nor narrators: no byline element renders at all") {
            val root = render(authors = emptyList(), narrators = emptyList())

            (root.querySelector(".bd-by") == null) shouldBe true
        }

        test("a byline name is reachable by keyboard") {
            val root = render(authors = listOf(BookContributor(id = "a1", name = "Author One")), narrators = emptyList())

            (root.querySelector(".bd-by-name") as HTMLElement).getAttribute("tabindex") shouldBe "0"
        }

        test("a byline name announces itself as a control, not a decoration") {
            val root = render(authors = listOf(BookContributor(id = "a1", name = "Author One")), narrators = emptyList())

            (root.querySelector(".bd-by-name") as HTMLElement).getAttribute("role") shouldBe "button"
        }

        test("Enter opens the contributor the name is for") {
            val opened = mutableListOf<String>()
            val root =
                render(
                    authors = listOf(BookContributor(id = "a1", name = "Author One")),
                    narrators = emptyList(),
                    onOpenContributor = { opened += it },
                )

            (root.querySelector(".bd-by-name") as HTMLElement).press("Enter")

            opened shouldBe listOf("a1")
        }

        test("Space opens the contributor too, as a button would") {
            val opened = mutableListOf<String>()
            val root =
                render(
                    authors = listOf(BookContributor(id = "a1", name = "Author One")),
                    narrators = emptyList(),
                    onOpenContributor = { opened += it },
                )

            (root.querySelector(".bd-by-name") as HTMLElement).press(" ")

            opened shouldBe listOf("a1")
        }
    })
