package com.calypsan.listenup.web.features.chaptereditor

import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import com.calypsan.listenup.client.presentation.chaptereditor.DriftProposal
import com.calypsan.listenup.client.presentation.chaptereditor.DriftRefusal
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

internal fun chapter(
    id: String = "c1",
    title: String = "Chapter One",
    startTime: Long = 0L,
    duration: Long = 60_000L,
) = Chapter(id = id, title = title, duration = duration, startTime = startTime)

/** Three chapters, a minute apart — enough for order, numbering and neighbours to be real. */
internal fun threeChapters() =
    listOf(
        chapter("c1", "The Boy Who Lived", 0L, 60_000L),
        chapter("c2", "The Vanishing Glass", 60_000L, 60_000L),
        chapter("c3", "The Letters from No One", 120_000L, 60_000L),
    )

@Suppress("LongParameterList")
internal fun editingChapters(
    chapters: List<Chapter> = threeChapters(),
    bookTitle: String = "Philosopher's Stone",
    bookDurationMs: Long = 180_000L,
    selectedChapterId: String? = null,
    isDirty: Boolean = false,
    canUndo: Boolean = false,
    isSaving: Boolean = false,
    changedElsewhere: Boolean = false,
    lockedChapterIds: Set<String> = emptySet(),
    drift: ChapterEditorUiState.DriftState? = null,
) = ChapterEditorUiState.Editing(
    bookTitle = bookTitle,
    chapters = chapters,
    bookDurationMs = bookDurationMs,
    selectedChapterId = selectedChapterId,
    isDirty = isDirty,
    canUndo = canUndo,
    isSaving = isSaving,
    changedElsewhere = changedElsewhere,
    lockedChapterIds = lockedChapterIds,
    drift = drift,
)

@Suppress("LongParameterList")
private fun page(
    state: ChapterEditorUiState,
    playheadMs: Long? = null,
    onSelect: (String?) -> Unit = {},
    onNudge: (String, Long) -> Unit = { _, _ -> },
    onSnapToPlayhead: (String, Long) -> Unit = { _, _ -> },
    onRetitle: (String, String) -> Unit = { _, _ -> },
    onRemove: (String) -> Unit = {},
    onAddAt: (Long, String) -> Unit = { _, _ -> },
    onToggleLock: (String) -> Unit = {},
    onBeginDrift: () -> Unit = {},
    onPinAnchor: (String, Long) -> Unit = { _, _ -> },
    onApplyDrift: () -> Unit = {},
    onCancelDrift: () -> Unit = {},
    onUndo: () -> Unit = {},
    onSave: () -> Unit = {},
    onLeave: () -> Unit = {},
    problem: String? = null,
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        ChapterEditorPage(
            state = state,
            playheadMs = playheadMs,
            onSelect = onSelect,
            onNudge = onNudge,
            onSnapToPlayhead = onSnapToPlayhead,
            onRetitle = onRetitle,
            onRemove = onRemove,
            onAddAt = onAddAt,
            onToggleLock = onToggleLock,
            onBeginDrift = onBeginDrift,
            onPinAnchor = onPinAnchor,
            onApplyDrift = onApplyDrift,
            onCancelDrift = onCancelDrift,
            onUndo = onUndo,
            onSave = onSave,
            onLeave = onLeave,
            problem = problem,
        )
    }
    return host
}

private fun rows(host: HTMLElement) = host.querySelectorAll(".chr").asList().filterIsInstance<HTMLElement>()

/** The control labelled [label] on row [index], or null when that row does not offer one. */
private fun rowAction(
    host: HTMLElement,
    index: Int,
    label: String,
): HTMLButtonElement? =
    rows(host)
        .getOrNull(index)
        ?.querySelectorAll("button")
        ?.asList()
        ?.filterIsInstance<HTMLButtonElement>()
        ?.firstOrNull { it.getAttribute("aria-label") == label }

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

private fun dialogButton(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("dialog button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/**
 * The chapter editor — every boundary in a book, and everything that can be done to one.
 *
 * What these pin: the list alone is sufficient (the spec's own requirement, with the timeline as
 * optional sugar), search narrows without renumbering, the playhead is offered only when it belongs
 * to this book, a refused save says which row is wrong and stays on screen, and the guided drift
 * flow is a proposal that moves nothing until it is applied.
 */
class ChapterEditorPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the header names the book and counts its chapters") {
            val host = page(editingChapters(bookTitle = "Mistborn"))

            host.querySelector(".ched-t")?.textContent shouldBe "Edit chapters"
            host.querySelector(".ched-sub")?.textContent shouldContain "Mistborn · 3 chapters"
        }

        test("every chapter is a row, in order, with its start time") {
            val host = page(editingChapters())

            rows(host).map { it.querySelector(".chr-t")?.textContent } shouldContainExactly
                listOf("The Boy Who Lived", "The Vanishing Glass", "The Letters from No One")
            rows(host).map { it.querySelector(".chr-n")?.textContent } shouldContainExactly listOf("1", "2", "3")
            rows(host)[1].querySelector(".chr-at")?.textContent shouldBe "0:01:00.0"
        }

        // ⛔ Numbering travels with the chapter, not with its position in whatever list is on
        // screen. Numbering the visible rows would relabel chapter 3 as chapter 1 the moment
        // someone typed.
        test("search narrows the list without renumbering it") {
            val host = page(editingChapters())

            val search = host.querySelector("#ched-search") as HTMLInputElement
            search.value = "Letters"
            search.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            rows(host).map { it.querySelector(".chr-t")?.textContent } shouldContainExactly
                listOf("The Letters from No One")
            rows(host).single().querySelector(".chr-n")?.textContent shouldBe "3"
        }

        // ⛔ The number is the point of search on a 311-chapter book: "3" must find chapter 3, not
        // every row whose title happens to contain a 3. Compose has matched on the number since the
        // editor shipped; the browser did not, and a reader typing a number got nothing.
        test("typing a chapter's number finds that chapter") {
            // ⛔ Titles with no digits in them, for the reason the next spec spells out.
            val many = (1..5).map { chapter("c$it", numberless(it), (it - 1) * 60_000L, 60_000L) }
            val host = page(editingChapters(chapters = many, bookDurationMs = 300_000L))

            val search = host.querySelector("#ched-search") as HTMLInputElement
            search.value = "3"
            search.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            rows(host).map { it.querySelector(".chr-n")?.textContent } shouldContainExactly listOf("3")
        }

        // ⛔ The titles carry NO digits. With "Chapter 13" as a title, a number match and a title
        // match are indistinguishable, and this spec would pass on the title alone — proving
        // nothing about the number path it exists to pin.
        test("a number match is exact, not a substring of the number") {
            val many = (1..20).map { chapter("c$it", numberless(it), (it - 1) * 60_000L, 60_000L) }
            val host = page(editingChapters(chapters = many, bookDurationMs = 1_200_000L))

            val search = host.querySelector("#ched-search") as HTMLInputElement
            search.value = "1"
            search.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            // Chapter 1 only — never 10 through 19, whose numbers merely start with a 1.
            rows(host).map { it.querySelector(".chr-n")?.textContent } shouldContainExactly listOf("1")
        }

        test("a search that matches nothing says so, and says what was searched for") {
            val host = page(editingChapters())

            val search = host.querySelector("#ched-search") as HTMLInputElement
            search.value = "Elantris"
            search.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            rows(host).shouldBeEmptyList()
            host.querySelector(".ched-none")?.textContent shouldBe "No chapters match “Elantris”."
        }

        test("selecting a row reports it, and the row says it is selected") {
            val selected = mutableListOf<String?>()
            val host = page(editingChapters(selectedChapterId = "c2"), onSelect = { selected += it })

            rows(host).map { it.getAttribute("aria-current") } shouldContainExactly listOf("false", "true", "false")

            rows(host)[0].click()
            awaitFrame()

            selected shouldContainExactly listOf("c1")
        }

        test("nudging moves a boundary a second in the direction pressed") {
            val nudges = mutableListOf<Pair<String, Long>>()
            val host = page(editingChapters(), onNudge = { id, delta -> nudges += id to delta })

            rowAction(host, 1, "Nudge back").shouldNotBeNull().click()
            rowAction(host, 1, "Nudge forward").shouldNotBeNull().click()
            awaitFrame()

            nudges shouldContainExactly listOf("c2" to -1_000L, "c2" to 1_000L)
        }

        // ⛔ Pressing a row's own control must not also select the row. Without stopPropagation the
        // row's click handler fires too, and Delete moves the selection onto the row being deleted.
        test("a row's controls act without also selecting the row") {
            val selected = mutableListOf<String?>()
            val nudges = mutableListOf<Pair<String, Long>>()
            val host =
                page(
                    editingChapters(),
                    onSelect = { selected += it },
                    onNudge = { id, delta -> nudges += id to delta },
                )

            rowAction(host, 2, "Nudge forward").shouldNotBeNull().click()
            awaitFrame()

            nudges shouldContainExactly listOf("c3" to 1_000L)
            selected shouldContainExactly emptyList()
        }

        // ⛔ Absent, not disabled. A position borrowed from a different book would write a number
        // from somewhere else entirely, so with no playhead for THIS book there is no control.
        test("nothing offers the playhead when this book is not the one playing") {
            val host = page(editingChapters(), playheadMs = null)

            rowAction(host, 0, "Set start at playhead").shouldBeNull()
            button(host, "Add chapter at playhead").shouldBeNull()
        }

        test("the playhead can be taken as a boundary's start when this book is playing") {
            val snaps = mutableListOf<Pair<String, Long>>()
            val host = page(editingChapters(), playheadMs = 61_500L, onSnapToPlayhead = { id, at -> snaps += id to at })

            rowAction(host, 2, "Set start at playhead").shouldNotBeNull().click()
            awaitFrame()

            snaps shouldContainExactly listOf("c3" to 61_500L)
        }

        test("the row the listener is inside is marked, and only that one") {
            val host = page(editingChapters(), playheadMs = 61_500L)

            rows(host).map { it.querySelector(".chr-now") != null } shouldContainExactly listOf(false, true, false)
        }

        test("a lock reads as state, not as a button that did nothing") {
            val toggled = mutableListOf<String>()
            val host = page(editingChapters(lockedChapterIds = setOf("c2")), onToggleLock = { toggled += it })

            rowAction(host, 0, "Lock chapter").shouldNotBeNull()
            rowAction(host, 1, "Unlock chapter").shouldNotBeNull().getAttribute("aria-pressed") shouldBe "true"
            rowAction(host, 0, "Lock chapter").shouldNotBeNull().getAttribute("aria-pressed") shouldBe "false"

            rowAction(host, 1, "Unlock chapter").shouldNotBeNull().click()
            awaitFrame()

            toggled shouldContainExactly listOf("c2")
        }

        test("renaming opens on the chapter's current title and reports the new one") {
            val renames = mutableListOf<Pair<String, String>>()
            val host = page(editingChapters(), onRetitle = { id, title -> renames += id to title })

            rowAction(host, 1, "Rename chapter").shouldNotBeNull().click()
            awaitFrame()

            val field = host.querySelector("#ced-chapter-title") as HTMLInputElement
            field.value shouldBe "The Vanishing Glass"
            field.value = "The Vanishing Cabinet"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            dialogButton(host, "Rename").shouldNotBeNull().click()
            awaitFrame()

            renames shouldContainExactly listOf("c2" to "The Vanishing Cabinet")
        }

        // ⛔ Refused here rather than stored and caught at save. The ViewModel refuses it too, but a
        // save that fails three edits later cannot say which edit did it.
        test("a blank title cannot be confirmed") {
            val host = page(editingChapters())

            rowAction(host, 0, "Rename chapter").shouldNotBeNull().click()
            awaitFrame()

            val field = host.querySelector("#ced-chapter-title") as HTMLInputElement
            field.value = "   "
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            dialogButton(host, "Rename").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        // ⛔ "Delete" reads as losing audio. It does not: the span joins the chapter before it.
        test("deleting asks first, and says where the span goes") {
            val removed = mutableListOf<String>()
            val host = page(editingChapters(), onRemove = { removed += it })

            rowAction(host, 2, "Delete chapter").shouldNotBeNull().click()
            awaitFrame()

            host.querySelector("dialog")?.textContent.shouldNotBeNull() shouldContain
                "Its span joins the chapter before it. The audio is untouched."
            removed shouldContainExactly emptyList()

            dialogButton(host, "Delete").shouldNotBeNull().click()
            awaitFrame()

            removed shouldContainExactly listOf("c3")
        }

        test("dismissing the delete confirmation removes nothing") {
            val removed = mutableListOf<String>()
            val host = page(editingChapters(), onRemove = { removed += it })

            rowAction(host, 0, "Delete chapter").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            removed shouldContainExactly emptyList()
        }

        test("Save is disabled until something changes, and says so while it is in flight") {
            button(page(editingChapters(isDirty = false)), "Save chapters")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
            button(page(editingChapters(isDirty = true)), "Save chapters")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe false

            val saving = page(editingChapters(isDirty = true, isSaving = true))
            saving.querySelector(".ched-status")?.textContent shouldBe "Saving…"
            button(saving, "Save chapters").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("an unsaved draft says so") {
            page(editingChapters(isDirty = true)).querySelector(".ched-status")?.textContent shouldBe "Unsaved"
            page(editingChapters(isDirty = false)).querySelector(".ched-status").shouldBeNull()
        }

        test("Undo is offered only when there is something to step back to") {
            button(page(editingChapters(canUndo = false)), "Undo").shouldNotBeNull().hasAttribute("disabled") shouldBe true
            button(page(editingChapters(canUndo = true)), "Undo").shouldNotBeNull().hasAttribute("disabled") shouldBe false
        }

        test("a set changed on another device is announced, and says the edits are kept") {
            val host = page(editingChapters(changedElsewhere = true))

            val banner = host.querySelector(".ched-elsewhere").shouldNotBeNull()
            banner.getAttribute("role") shouldBe "status"
            banner.getAttribute("aria-live") shouldBe "polite"
            banner.textContent.shouldNotBeNull() shouldContain "Your edits are kept. Review before saving."
        }

        // ⛔ role=alert and it stays put. A refused save means nothing left the device and a
        // specific row needs fixing — a message that fades takes the row number with it.
        test("a refused save says which row is wrong, and does not fade") {
            val host = page(editingChapters(), problem = "Chapter 2 needs a title.")

            val alert = host.querySelector(".ched-problem").shouldNotBeNull()
            alert.getAttribute("role") shouldBe "alert"
            alert.textContent shouldBe "Chapter 2 needs a title."
        }

        test("a book that was never chaptered offers a first boundary rather than a dead end") {
            val added = mutableListOf<Pair<Long, String>>()
            val host =
                page(
                    editingChapters(chapters = emptyList()),
                    playheadMs = 12_000L,
                    onAddAt = { at, title -> added += at to title },
                )

            host.querySelector(".ched-empty").shouldNotBeNull()
            button(host, "Add first chapter at playhead").shouldNotBeNull().click()
            awaitFrame()

            added shouldContainExactly listOf(12_000L to "New chapter")
        }

        test("an unchaptered book with nothing playing says how to place the first boundary") {
            val host = page(editingChapters(chapters = emptyList()), playheadMs = null)

            button(host, "Add first chapter at playhead").shouldBeNull()
            host.querySelector(".ched-none")?.textContent shouldBe "Play this book to place the first boundary."
        }

        test("a page still loading draws nothing it does not know yet") {
            val host = page(ChapterEditorUiState.Loading)

            host.querySelector(".ched-skel").shouldNotBeNull()
            rows(host).shouldBeEmptyList()
        }

        test("a book that cannot be loaded says so, and offers the way back") {
            val leaves = mutableListOf<Unit>()
            val host = page(ChapterEditorUiState.Error("That book is gone."), onLeave = { leaves += Unit })

            host.querySelector(".empty")?.textContent.shouldNotBeNull() shouldContain "That book is gone."
            button(host, "Back to the book").shouldNotBeNull().click()
            awaitFrame()

            leaves.size shouldBe 1
        }

        // ⛔ Nothing to interpolate between on an empty or single-chapter book.
        test("Fix drift is refused on a book with nothing to interpolate across") {
            button(page(editingChapters(chapters = threeChapters().take(1))), "Fix drift")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
            button(page(editingChapters()), "Fix drift").shouldNotBeNull().hasAttribute("disabled") shouldBe false
        }

        test("Fix drift opens the flow") {
            val begins = mutableListOf<Unit>()
            val host = page(editingChapters(), onBeginDrift = { begins += Unit })

            button(host, "Fix drift").shouldNotBeNull().click()
            awaitFrame()

            begins.size shouldBe 1
        }

        test("the drift flow cannot be re-opened while it is already open") {
            val host = page(editingChapters(drift = ChapterEditorUiState.DriftState()))

            button(host, "Fix drift").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        // ⛔ Two separate prerequisites, named separately. One message covering both would leave
        // the reader guessing which half they are missing.
        test("pinning says which of the two things it is still missing") {
            page(editingChapters(drift = ChapterEditorUiState.DriftState()), playheadMs = 1_000L)
                .querySelector(".drift-need")
                ?.textContent shouldBe "Choose a chapter in the list, play to where you hear it start, then pin."

            page(
                editingChapters(selectedChapterId = "c2", drift = ChapterEditorUiState.DriftState()),
                playheadMs = null,
            ).querySelector(".drift-need")
                ?.textContent shouldBe "Play the book to the spot you can hear, then pin."
        }

        test("pinning reports the selected chapter at the playhead") {
            val pins = mutableListOf<Pair<String, Long>>()
            val host =
                page(
                    editingChapters(selectedChapterId = "c2", drift = ChapterEditorUiState.DriftState()),
                    playheadMs = 58_200L,
                    onPinAnchor = { id, at -> pins += id to at },
                )

            button(host, "Pin first anchor at playhead").shouldNotBeNull().click()
            awaitFrame()

            pins shouldContainExactly listOf("c2" to 58_200L)
        }

        test("the second pin asks for the second anchor, and both are named by their number") {
            val host =
                page(
                    editingChapters(
                        selectedChapterId = "c3",
                        drift =
                            ChapterEditorUiState.DriftState(
                                proposal = DriftProposal(first = ChapterAnchor("c1", 2_000L)),
                            ),
                    ),
                    playheadMs = 121_000L,
                )

            button(host, "Pin second anchor at playhead").shouldNotBeNull()
            host.querySelectorAll(".drift-slot-v").asList().map { it.textContent } shouldContainExactly
                listOf("Chapter 1 · 0:00:02.0", "Not pinned")
        }

        // ⛔ Nothing moves until Apply. The summary describes a proposal.
        test("the preview says how many boundaries move and how far the drift spreads") {
            val host =
                page(
                    editingChapters(
                        drift =
                            ChapterEditorUiState.DriftState(
                                proposal = DriftProposal(first = ChapterAnchor("c1", 0L)),
                                preview =
                                    DriftPreview.Ready(
                                        corrected = threeChapters(),
                                        affectedCount = 311,
                                        firstOffsetMs = 0L,
                                        lastOffsetMs = 42_000L,
                                    ),
                            ),
                    ),
                )

            host.querySelector(".drift-sum")?.textContent shouldBe
                "311 boundaries move. Drift spreads +0:42.0 across the book."
        }

        // A single anchor is the degenerate case — a constant shift — and quoting a spread of zero
        // would read as "this changes nothing" for a correction that moves every boundary.
        test("a constant shift is quoted as one number, not as a spread of nothing") {
            val host =
                page(
                    editingChapters(
                        drift =
                            ChapterEditorUiState.DriftState(
                                proposal = DriftProposal(first = ChapterAnchor("c1", 0L)),
                                preview =
                                    DriftPreview.Ready(
                                        corrected = threeChapters(),
                                        affectedCount = 311,
                                        firstOffsetMs = 3_000L,
                                        lastOffsetMs = 3_000L,
                                    ),
                            ),
                    ),
                )

            host.querySelector(".drift-sum")?.textContent shouldBe "311 boundaries move by +0:03.0."
        }

        // ⛔ A sentence, not a greyed-out button. A mis-set anchor is the likeliest mistake here,
        // and "Apply is disabled" says nothing about which of the two is wrong.
        test("a refused proposal says why, and cannot be applied") {
            val host =
                page(
                    editingChapters(
                        drift =
                            ChapterEditorUiState.DriftState(
                                proposal = DriftProposal(first = ChapterAnchor("c1", 0L)),
                                preview = DriftPreview.Refused(DriftRefusal.InvertedAnchors),
                            ),
                    ),
                )

            val refusal = host.querySelector(".drift-refused").shouldNotBeNull()
            refusal.getAttribute("role") shouldBe "alert"
            refusal.textContent shouldContain "Audio does not run backwards"
            button(host, "Apply correction").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("Apply is refused until there is a preview to apply") {
            val host = page(editingChapters(drift = ChapterEditorUiState.DriftState()))

            button(host, "Apply correction").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("a ready proposal can be applied, and the flow can be abandoned instead") {
            val applied = mutableListOf<Unit>()
            val cancelled = mutableListOf<Unit>()
            val ready =
                ChapterEditorUiState.DriftState(
                    proposal = DriftProposal(first = ChapterAnchor("c1", 0L)),
                    preview =
                        DriftPreview.Ready(
                            corrected = threeChapters(),
                            affectedCount = 3,
                            firstOffsetMs = 0L,
                            lastOffsetMs = 1_000L,
                        ),
                )

            val applyHost = page(editingChapters(drift = ready), onApplyDrift = { applied += Unit })
            button(applyHost, "Apply correction").shouldNotBeNull().click()
            awaitFrame()
            applied.size shouldBe 1

            val cancelHost = page(editingChapters(drift = ready), onCancelDrift = { cancelled += Unit })
            button(cancelHost, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            cancelled.size shouldBe 1
        }
    })

private fun List<HTMLElement>.shouldBeEmptyList() {
    size shouldBe 0
}

/** A title with no digits anywhere in it, so a number search cannot match it by accident. */
private fun numberless(n: Int): String =
    listOf(
        "The Boy Who Lived",
        "The Vanishing Glass",
        "The Letters from No One",
        "Keeper of the Keys",
        "Diagon Alley",
        "The Journey from Platform",
        "The Sorting Hat",
        "The Potions Master",
        "The Midnight Duel",
        "Hallowe'en",
        "Quidditch",
        "The Mirror of Erised",
        "Nicolas Flamel",
        "Norbert the Ridgeback",
        "The Forbidden Forest",
        "Through the Trapdoor",
        "The Man with Two Faces",
        "Dobby's Warning",
        "The Burrow",
        "The Whomping Willow",
    )[(n - 1) % 20]
