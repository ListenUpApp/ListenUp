package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** The Overview's first side panel says who cannot see the book — admins only, never when held. */
class BookDetailVisibilityTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val opened = mutableListOf<String>()
        var restores = 0
        var pickers = 0
        beforeTest {
            opened.clear()
            restores = 0
            pickers = 0
        }

        fun rendered(state: BookDetailUiState): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = state,
                    tab = "overview",
                    onSelectTab = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onRetryConnection = {},
                    pickers = BookPickers(onShowCollectionPicker = { pickers++ }),
                    onRestoreToAllBooks = { restores++ },
                    onOpenCollection = { opened += it },
                )
            }

        fun with(visibility: BookVisibility?) = readyBook().copy(isAdmin = true, visibility = visibility)

        fun panel(host: HTMLElement) = host.querySelector(".bd-side .bd-vis") as HTMLElement?

        fun button(
            host: HTMLElement,
            text: String,
        ) = host
            .querySelectorAll("button")
            .asList()
            .map { it as HTMLButtonElement }
            .single { it.textContent?.trim() == text }

        test("it is the first panel in the side column, above Details") {
            val host = rendered(with(BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone)))
            host
                .querySelectorAll(".bd-side h2")
                .asList()
                .map { it.textContent?.trim() }
                .take(2) shouldBe
                listOf("Visibility", "Details")
            panel(host)!!.textContent!!.contains("Admins only") shouldBe true
        }

        test("named members, the reason, and the collection as a link") {
            val host =
                rendered(
                    with(BookVisibility.Restricted(listOf(CollectionRef("c1", "Sci-Fi Club")), HiddenFrom.Members(listOf("Alice", "Ben")))),
                )
            val text = panel(host)!!.textContent!!
            text.contains("Hidden from Alice and Ben") shouldBe true
            text.contains("Only people in Sci-Fi Club can see it.") shouldBe true
            text.contains("In 1 collection") shouldBe true
            (panel(host)!!.querySelector(".pill") as HTMLElement).click()
            opened shouldBe listOf("c1")
        }

        test("a long list stops after three names until Show all") {
            val three =
                listOf(CollectionRef("c1", "Bedtime Stories"), CollectionRef("c2", "Classics Shelf"), CollectionRef("c3", "Family"))
            val host = rendered(with(BookVisibility.Restricted(three, HiddenFrom.Members(listOf("Alice", "Dev", "Hana", "Lee", "Zoe")))))
            panel(host)!!.textContent!!.contains("Hidden from Alice, Dev, Hana and 2 others") shouldBe true
            panel(host)!!.textContent!!.contains("Anyone in at least one of these collections can see it.") shouldBe true
            panel(host)!!.textContent!!.contains("In 3 collections") shouldBe true
            button(host, "Show all 5").click()
            awaitFrame()
            panel(host)!!.textContent!!.contains("Hidden from Alice, Dev, Hana, Lee and Zoe") shouldBe true
            host.querySelectorAll("button").asList().none { it.textContent?.trim() == "Show all 5" } shouldBe true
        }

        test("every member, and no member") {
            rendered(with(BookVisibility.Restricted(listOf(CollectionRef("c1", "Family")), HiddenFrom.Nobody))).let {
                panel(it)!!.textContent!!.contains("Every member can see it") shouldBe true
                panel(it)!!.textContent!!.contains("Every member is in Family.") shouldBe true
            }
            mounts.disposeAll()
            rendered(with(BookVisibility.Restricted(listOf(CollectionRef("c1", "Drafts")), HiddenFrom.Everyone))).let {
                panel(it)!!.textContent!!.contains("Hidden from all members") shouldBe true
                panel(it)!!.textContent!!.contains("No member is in Drafts yet, so only admins can see it.") shouldBe true
            }
        }

        test("a stranded book offers both fixes, and Show to all members does not ask first") {
            val host = rendered(with(BookVisibility.Stranded))
            panel(host)!!.textContent!!.contains("Hidden from all members") shouldBe true
            panel(host)!!.textContent!!.contains("It isn’t in any collection, so only admins can see it.") shouldBe true
            button(host, "Show to all members").click()
            button(host, "Add to a collection").click()
            restores shouldBe 1
            pickers shouldBe 1
            (host.querySelector("[role=dialog]") == null) shouldBe true
        }

        test("while the restore is in flight the button says so and cannot be pressed twice") {
            val host = rendered(with(BookVisibility.Stranded).copy(isRestoringToAllBooks = true))
            button(host, "Showing to all members…").disabled shouldBe true
        }

        test("nothing for a member, a public book or a held one") {
            (panel(rendered(with(null))) == null) shouldBe true
            mounts.disposeAll()
            (panel(rendered(with(BookVisibility.Public))) == null) shouldBe true
            mounts.disposeAll()
            (panel(rendered(with(BookVisibility.Held).copy(isHeld = true, canPlay = false))) == null) shouldBe true
        }

        // Held wins on the page too, not only in the classifier: even if the two Room flows disagree
        // for a frame, a held book never shows a stranded fix beside the inbox's Release.
        test("a held book shows no panel even while its visibility still reads stranded") {
            (panel(rendered(with(BookVisibility.Stranded).copy(isHeld = true, canPlay = false))) == null) shouldBe true
        }

        // Each fix succeeds by unmounting the stranded block, and with it the button that had focus.
        // Without a hand-off focus falls to <body>; the house pattern is the page's own H1.
        fun focusLandsOnHeadingWhenStrandedBecomes(after: BookVisibility) {
            test("when the stranded block gives way to ${after::class.simpleName}, the heading takes focus") {
                var state by mutableStateOf<BookDetailUiState>(with(BookVisibility.Stranded))
                val host =
                    mounts.mount {
                        BookDetailPage(
                            state = state,
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            onPlay = {},
                            onRetryConnection = {},
                        )
                    }

                button(host, "Show to all members").focus()
                document.activeElement shouldBe button(host, "Show to all members")
                state = with(after)
                awaitFrame()
                awaitFrame()

                (host.querySelector(".bd-vis-stranded") == null) shouldBe true
                document.activeElement shouldBe host.querySelector(".bd-head h1")
            }
        }

        // "Show to all members": the local reconcile turns the book Public at once.
        focusLandsOnHeadingWhenStrandedBecomes(BookVisibility.Public)
        // "Add to a collection": the picker closes and the book is now Restricted.
        focusLandsOnHeadingWhenStrandedBecomes(BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone))
    })
