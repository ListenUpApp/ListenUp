package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.asList

/**
 * Every person credited on a book, reachable from its byline — Android's "All credits" sheet and iOS's
 * Cast & Credits, which web lacked (a parity stop). Each row shows the person's framed photo in a
 * circle, or their initials, with their name and roles, and opens their page.
 */
class BookDetailCreditsTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val cast =
            listOf(
                BookContributor(
                    id = "a1",
                    name = "James S. A. Corey",
                    roles = listOf("author"),
                    imagePath = "contributors/corey.jpg",
                    imageFrame = ImageFrame(0.5, 0.3, 1.4),
                ),
                BookContributor(id = "n1", name = "Jefferson Mays", roles = listOf("narrator")),
                BookContributor(id = "e1", name = "Ann Editor", roles = listOf("editor", "translator")),
            )

        fun render(
            allContributors: List<BookContributor> = cast,
            authors: List<BookContributor> = cast.take(1),
            narrators: List<BookContributor> = cast.drop(1).take(1),
            onOpenContributor: (String) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = readyBook(authors = authors, narrators = narrators, allContributors = allContributors),
                    onPlayFromChapter = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onOpenContributor = onOpenContributor,
                    onRetryConnection = {},
                )
            }

        fun HTMLElement.allCredits(): HTMLElement? =
            querySelectorAll("button").asList().map { it as HTMLElement }.firstOrNull { it.textContent == "All credits" }

        test("the byline offers All credits, and it opens a dialog listing everyone credited") {
            val host = render()
            host.allCredits().shouldNotBeNull().click()
            awaitFrame()

            val dialog = (host.querySelector("dialog.bd-credits") as? HTMLDialogElement).shouldNotBeNull()
            dialog.open shouldBe true
            dialog.querySelector(".dlg-t")!!.textContent shouldBe "Credits"
            dialog.textContent!! shouldContain "3 contributors"
            // By role (spec D8): a heading per role, then its people; a person in two roles is under both.
            dialog.querySelectorAll(".credit-role-h").asList().map { it.textContent } shouldBe
                listOf("Author", "Narrator", "Editor", "Translator")
            dialog.querySelectorAll(".credit-row").asList().map {
                (it as HTMLElement).querySelector(".credit-name")!!.textContent
            } shouldBe listOf("James S. A. Corey", "Jefferson Mays", "Ann Editor", "Ann Editor")
        }

        test("the Details panel's All credits row opens the same dialog") {
            val host = render()
            host
                .querySelectorAll(".bd-detail-link")
                .asList()
                .map { it as HTMLElement }
                .first { it.getAttribute("aria-label") == "All credits (3)" }
                .click()
            awaitFrame()
            host.querySelector("dialog.bd-credits").shouldNotBeNull()
        }

        test("a credit with a photo shows it framed in a circle; one without shows initials") {
            val host = render()
            host.allCredits()!!.click()
            awaitFrame()

            val rows = host.querySelectorAll(".credit-row").asList().map { it as HTMLElement }
            val img = (rows[0].querySelector(".credit-photo img.framed-img") as? HTMLImageElement).shouldNotBeNull()
            img.getAttribute("src") shouldBe "/api/v1/contributors/a1/photo?v=contributors/corey.jpg"
            rows[1].querySelector(".credit-photo img").shouldBeNull()
            rows[1].querySelector(".credit-photo")!!.textContent shouldBe "JM"
        }

        test("choosing a credit opens that person and closes the list") {
            val opened = mutableListOf<String>()
            val host = render(onOpenContributor = { opened += it })
            host.allCredits()!!.click()
            awaitFrame()

            (host.querySelectorAll(".credit-row").item(1) as HTMLElement).click()
            awaitFrame()

            opened shouldBe listOf("n1")
            host.querySelector("dialog.bd-credits").shouldBeNull()
        }

        test("with no credits stored, the list falls back to the authors and narrators") {
            val host = render(allContributors = emptyList())
            host.allCredits()!!.click()
            awaitFrame()

            host.querySelectorAll(".credit-row").length shouldBe 2
        }

        test("a book that credits nobody offers no All credits") {
            val host = render(allContributors = emptyList(), authors = emptyList(), narrators = emptyList())
            host.allCredits().shouldBeNull()
        }
    })
