package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private const val CANCEL = 0

private const val CONFIRM = 1

private fun dialogButton(
    host: HTMLElement,
    index: Int,
): HTMLElement = host.querySelectorAll("dialog.dlg .dlg-actions button").item(index) as HTMLElement

private fun dialogTitle(host: HTMLElement): String? = host.querySelector("dialog.dlg .dlg-t")?.textContent

private fun dialogBody(host: HTMLElement): String? = host.querySelector("dialog.dlg .dlg-p")?.textContent

private fun item(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll(".menu-i")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/**
 * Book Detail's More menu on the web (spec §2.7): "More ▾" after Resume and Add to shelf, the shared rule's groups —
 * Share; Progress; Edit; Admin; then Delete from server alone, red and unheaded — and the two progress actions that
 * erase a position asking first, in the natives' words.
 */
class BookActionsMenuTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun rendered(
            ready: BookDetailUiState.Ready,
            onEdit: () -> Unit = {},
            onEditChapters: () -> Unit = {},
            onMatchMetadata: () -> Unit = {},
            onShare: () -> Unit = {},
            onDiscardProgress: () -> Unit = {},
            onRestart: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = ready,
                    onPlayFromChapter = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onRetryConnection = {},
                    onEdit = onEdit,
                    onEditChapters = onEditChapters,
                    onMatchMetadata = onMatchMetadata,
                    onShare = onShare,
                    onDiscardProgress = onDiscardProgress,
                    onRestart = onRestart,
                )
            }

        fun trigger(host: HTMLElement): HTMLButtonElement? = host.querySelector(".bd-actions .bd-menu") as? HTMLButtonElement

        suspend fun open(host: HTMLElement): HTMLElement {
            val button = trigger(host).shouldNotBeNull()
            button.textContent?.trim() shouldBe "More"
            // Unambiguous beside the phone tab bar's own "More": the book's name says whose actions these are.
            button.getAttribute("aria-label") shouldBe "More actions for The Institute"
            button.click()
            awaitFrame()
            return host
        }

        fun groups(host: HTMLElement): List<Pair<String?, List<String>>> =
            host.querySelector("[role=menu]")!!.children.asList().map { child ->
                if (child.getAttribute("role") == "group") {
                    child.getAttribute("aria-label") to
                        child.querySelectorAll(".menu-i").asList().map { it.textContent!!.trim() }
                } else {
                    null to listOf(child.textContent!!.trim())
                }
            }

        test("a member reading the book gets Share, then Progress; no Edit, Admin or Delete") {
            val host =
                open(rendered(readyBook(readingStage = ReadingStage.InProgress, canEditMetadata = false).copy(progress = 0.4f)))
            groups(host) shouldBe
                listOf(
                    null to listOf("Share…"),
                    "Progress" to listOf("Mark as finished…", "Restart from the beginning…", "Mark as not started…"),
                )
            item(host, "Add to shelf").shouldBeNull()
        }

        test("an untouched book offers only Mark as finished under Progress") {
            val host = open(rendered(readyBook(canEditMetadata = false)))
            groups(host) shouldBe listOf(null to listOf("Share…"), "Progress" to listOf("Mark as finished…"))
        }

        test("a finished book drops Mark as finished and keeps the two resets") {
            val host = open(rendered(readyBook(readingStage = ReadingStage.Finished, canEditMetadata = false)))
            groups(host)[1] shouldBe ("Progress" to listOf("Restart from the beginning…", "Mark as not started…"))
        }

        test("the admin group is absent for a member, even one who may edit") {
            val host = open(rendered(readyBook(canEditMetadata = true)))
            groups(host).map { it.first } shouldBe listOf(null, "Progress", "Edit")
            host.querySelector("[role=menu] [aria-label='Admin']").shouldBeNull()
            host.querySelector("[role=menu] .is-danger").shouldBeNull()
        }

        test("an admin gets Admin, then Delete alone, with no heading, in the danger colour") {
            val host = open(rendered(readyBook(canEditMetadata = true).copy(isAdmin = true)))
            val all = groups(host)
            all[3] shouldBe ("Admin" to listOf("Add to collection…"))
            all.last() shouldBe ("Delete" to listOf("Delete from server…"))
            host.querySelector("[role=menu] [aria-label='Delete'] .menu-h").shouldBeNull()
            host.querySelector("[role=menu] .menu-i.is-danger")?.textContent?.trim() shouldBe "Delete from server…"
        }

        test("Edit details, Match details and Edit chapters live in the menu, not the hero") {
            var edits = 0
            var matches = 0
            var chapters = 0
            val host =
                rendered(
                    readyBook(canEditMetadata = true),
                    onEdit = { edits++ },
                    onMatchMetadata = { matches++ },
                    onEditChapters = { chapters++ },
                )
            host.querySelector(".bd-actions [aria-label='Edit book']").shouldBeNull()
            host.querySelector(".bd-actions [aria-label='Match details']").shouldBeNull()
            listOf("Edit details", "Match details…", "Edit chapters").forEach { label ->
                open(host)
                item(host, label).shouldNotBeNull().click()
                awaitFrame()
            }
            edits shouldBe 1
            matches shouldBe 1
            chapters shouldBe 1
        }

        test("a held book has no More at all") {
            trigger(rendered(readyBook().copy(isAdmin = true, isHeld = true))).shouldBeNull()
        }

        test("while a progress write is in flight, the progress items wait and the rest do not") {
            var shares = 0
            val host = open(rendered(readyBook(readingStage = ReadingStage.InProgress).copy(isRestarting = true), onShare = { shares++ }))
            item(host, "Mark as finished…").shouldNotBeNull().disabled shouldBe true
            item(host, "Share…").shouldNotBeNull().hasAttribute("disabled") shouldBe false
            item(host, "Mark as finished…")!!.click()
            awaitFrame()
            host.querySelector("dialog.dlg").shouldBeNull()
            // A disabled item leaves the menu open; Share still answers.
            item(host, "Share…")!!.click()
            awaitFrame()
            shares shouldBe 1
        }

        test("Mark as not started asks first, in the natives' words, and only the confirm clears") {
            var discarded = 0
            val host =
                open(rendered(readyBook(readingStage = ReadingStage.InProgress).copy(progress = 0.4f), onDiscardProgress = { discarded++ }))
            item(host, "Mark as not started…").shouldNotBeNull().click()
            awaitFrame()
            dialogTitle(host) shouldBe "Mark as not started?"
            dialogBody(host) shouldBe "Your progress on this book is cleared."
            discarded shouldBe 0
            dialogButton(host, CONFIRM).click()
            awaitFrame()
            discarded shouldBe 1
        }

        test("Restart asks first, in the natives' words, and Cancel changes nothing") {
            var restarted = 0
            val host = open(rendered(readyBook(readingStage = ReadingStage.InProgress).copy(progress = 0.4f), onRestart = { restarted++ }))
            item(host, "Restart from the beginning…").shouldNotBeNull().click()
            awaitFrame()
            dialogTitle(host) shouldBe "Restart from the beginning?"
            dialogBody(host) shouldBe "Your place goes back to the start of the book."
            dialogButton(host, CANCEL).click()
            awaitFrame()
            restarted shouldBe 0
            host.querySelector("dialog.dlg").shouldBeNull()
        }

        test("items are real menu items, and choosing one closes the menu") {
            val host = open(rendered(readyBook(readingStage = ReadingStage.InProgress).copy(progress = 0.4f)))
            host.querySelectorAll(".menu-i").asList().filterIsInstance<HTMLElement>().forEach {
                it.tagName.lowercase() shouldBe "button"
                it.getAttribute("role") shouldBe "menuitem"
            }
            item(host, "Share…")!!.click()
            awaitFrame()
            host.querySelector(".menu").shouldBeNull()
        }

        test("the trigger says whether it is open, for a reader who cannot see it") {
            val host = rendered(readyBook())
            trigger(host)!!.getAttribute("aria-expanded") shouldBe "false"
            open(host)
            trigger(host)!!.getAttribute("aria-expanded") shouldBe "true"
        }
    })
