package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.CurrentChapter
import com.calypsan.listenup.client.presentation.bookdetail.FinishedSpan
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * The top of Book Detail (spec §2.9, §3.2.1): the Resume unit says where you are, the primary says what to do next
 * by reading stage, and Add to shelf is a visible button rather than a menu item.
 */
class BookDetailHeroTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val inProgress =
            readyBook().copy(
                readingStage = ReadingStage.InProgress,
                primaryAction = PrimaryAction.Resume,
                progress = 0.11f,
                currentChapter = CurrentChapter(index = 5, number = 6, title = "Bridge Four", startMs = 0, elapsedInChapterMs = 0),
                timeRemainingMs = (40 * 60 + 31) * 60_000L,
            )

        fun rendered(
            state: BookDetailUiState.Ready,
            onAddToShelf: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = state,
                    tab = "overview",
                    onSelectTab = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onRetryConnection = {},
                    pickers = BookPickers(onShowShelfPicker = onAddToShelf),
                )
            }

        fun primary(root: HTMLElement) = root.querySelector(".bd-primary") as HTMLButtonElement

        test("in progress: the unit names the chapter and says percent and time left") {
            val root = rendered(inProgress)
            root.querySelector(".bd-unit-ch")!!.textContent shouldBe "Ch. 6 · Bridge Four"
            root.querySelector(".bd-unit-left")!!.textContent shouldBe "11% · 40h 31m left"
            root.querySelector(".bd-unit [role=progressbar]")!!.getAttribute("aria-valuenow") shouldBe "11"
        }

        test("Resume is a button with no disabled attribute at all (N8)") {
            val root = rendered(inProgress)
            primary(root).textContent!! shouldContain "Resume"
            primary(root).hasAttribute("disabled") shouldBe false
        }

        test("finished says Listen again, still enabled, and shows the finished span instead of a bar") {
            val root =
                rendered(
                    inProgress.copy(
                        readingStage = ReadingStage.Finished,
                        primaryAction = PrimaryAction.ListenAgain,
                        finishedSpan = FinishedSpan(1_770_854_400_000L, 1_772_496_000_000L),
                    ),
                )
            primary(root).textContent!! shouldContain "Listen again"
            primary(root).hasAttribute("disabled") shouldBe false
            root.querySelector(".bd-unit [role=progressbar]") shouldBe null
            root.querySelector(".bd-unit-finished")!!.textContent!! shouldContain "Finished ·"
            root.querySelector(".bd-unit-finished")!!.textContent!! shouldContain " – "
        }

        test("finished with no recorded date still says Finished") {
            val root = rendered(inProgress.copy(readingStage = ReadingStage.Finished, primaryAction = PrimaryAction.ListenAgain))
            root.querySelector(".bd-unit-finished")!!.textContent shouldBe "Finished"
        }

        test("unavailable offline is the only disabled primary, and it says why instead of vanishing") {
            val root = rendered(inProgress.copy(primaryAction = PrimaryAction.UnavailableOffline, canPlay = false))
            primary(root).textContent!! shouldContain "Unavailable offline"
            primary(root).hasAttribute("disabled") shouldBe true
        }

        test("not started: Play, and no unit") {
            val root = rendered(readyBook())
            primary(root).textContent!! shouldContain "Play"
            root.querySelector(".bd-unit") shouldBe null
        }

        test("Add to shelf is a visible button, not a menu item") {
            var asked = false
            val root = rendered(inProgress, onAddToShelf = { asked = true })
            (root.querySelector(".bd-shelf") as HTMLButtonElement).click()
            asked shouldBe true
        }

        test("the subtitle shows under the title (b9)") {
            val root = rendered(inProgress.copy(subtitle = "Book One of the Stormlight Archive"))
            root.querySelector(".bd-subtitle")!!.textContent shouldBe "Book One of the Stormlight Archive"
        }

        test("the stats line reads length, year, abridgement and the chapter count") {
            val root = rendered(readyBook())
            root.querySelector(".bd-stats")!!.textContent!! shouldContain "· 2019 · Unabridged · 33 chapters"
        }

        test("a held book has no unit and no primary") {
            val root = rendered(inProgress.copy(isHeld = true, isAdmin = true, canPlay = false))
            root.querySelector(".bd-unit") shouldBe null
            root.querySelector(".bd-primary") shouldBe null
            root.querySelector(".bd-shelf") shouldBe null
        }
    })
