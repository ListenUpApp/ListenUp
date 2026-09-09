package com.calypsan.listenup.web.features.seriesedit

import com.calypsan.listenup.client.presentation.seriesedit.MAX_MERGE_CANDIDATES
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.core.SeriesId
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
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

internal fun seriesCandidate(
    id: String = "s-mistborn",
    displayName: String = "Mistborn",
) = SeriesCandidate(id = SeriesId(id), displayName = displayName, bookCount = 0)

@Suppress("LongParameterList")
internal fun editingSeries(
    name: String = "The Stormlight Archive",
    description: String = "",
    coverPath: String? = null,
    pendingCoverData: ByteArray? = null,
    bookCount: Int = 5,
    error: String? = null,
    isLoading: Boolean = false,
    isSaving: Boolean = false,
    isUploadingCover: Boolean = false,
    hasChanges: Boolean = false,
    mergeInProgress: Boolean = false,
    mergeDialogVisible: Boolean = false,
    mergeQuery: String = "",
) = SeriesEditUiState(
    isLoading = isLoading,
    isSaving = isSaving,
    isUploadingCover = isUploadingCover,
    error = error,
    seriesId = "s-stormlight",
    name = name,
    description = description,
    coverPath = coverPath,
    pendingCoverData = pendingCoverData,
    bookCount = bookCount,
    mergeInProgress = mergeInProgress,
    mergeDialogVisible = mergeDialogVisible,
    mergeQuery = mergeQuery,
    hasChanges = hasChanges,
)

private fun page(
    state: SeriesEditUiState,
    mergeCandidates: List<SeriesCandidate> = emptyList(),
    onEvent: (SeriesEditUiEvent) -> Unit = {},
    onMergeQuery: (String) -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        SeriesEditPage(
            state = state,
            mergeCandidates = mergeCandidates,
            onEvent = onEvent,
            onMergeQuery = onMergeQuery,
        )
    }
    return host
}

private fun input(
    host: HTMLElement,
    id: String,
) = host.querySelector("#$id") as HTMLInputElement

private fun type(
    host: HTMLElement,
    id: String,
    typed: String,
) {
    val field = input(host, id)
    field.value = typed
    field.dispatchEvent(Event("input", EventInit(bubbles = true)))
}

/** The button whose label is exactly [label]; null when nothing on the page says it. */
private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/**
 * The button labelled [label] *inside the open dialog*.
 *
 * ⛔ Not [button]. "Cancel" names a control on the page as well as one in the dialog over it, and a
 * document-wide lookup finds the page's.
 */
private fun dialogButton(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("dialog button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

private fun saveButton(host: HTMLElement) = host.querySelector(".edit-actions button[type=submit]") as HTMLButtonElement

private fun results(host: HTMLElement) = host.querySelectorAll(".sed-result").asList().filterIsInstance<HTMLButtonElement>()

/**
 * Series Edit — the form over one series, and the one thing on it that cannot be taken back.
 *
 * What these pin: the form reflects the ViewModel and nothing else; each field reports its own
 * change; Save is honest about whether there is anything to save; the cover section answers
 * *whether this series has artwork* rather than borrowing a book's; and the merge picker asks
 * twice, says what it will cost, and admits when its list is not the whole list.
 */
class SeriesEditPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the form shows the series it was given") {
            val host = page(editingSeries(name = "Mistborn", description = "Ash falls from the sky."))

            input(host, "sed-name").value shouldBe "Mistborn"
            (host.querySelector("#sed-description") as HTMLTextAreaElement).value shouldBe "Ash falls from the sky."
        }

        test("each field reports its own change") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host = page(editingSeries(), onEvent = { seen += it })

            type(host, "sed-name", "one")
            val area = host.querySelector("#sed-description") as HTMLTextAreaElement
            area.value = "two"
            area.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            seen shouldContainExactly
                listOf(
                    SeriesEditUiEvent.NameChanged("one"),
                    SeriesEditUiEvent.DescriptionChanged("two"),
                )
        }

        test("Save is disabled until something changes") {
            page(editingSeries(hasChanges = false)).let { saveButton(it).hasAttribute("disabled") shouldBe true }
        }

        test("Save is offered once something has changed") {
            page(editingSeries(hasChanges = true)).let { saveButton(it).hasAttribute("disabled") shouldBe false }
        }

        test("Save says so while it is in flight, and cannot be pressed twice") {
            val host = page(editingSeries(hasChanges = true, isSaving = true))

            saveButton(host).textContent shouldBe "Saving…"
            saveButton(host).hasAttribute("disabled") shouldBe true
        }

        test("submitting the form saves") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host = page(editingSeries(hasChanges = true), onEvent = { seen += it })

            saveButton(host).click()
            awaitFrame()

            seen shouldContainExactly listOf(SeriesEditUiEvent.SaveClicked)
        }

        // ⛔ A <button> with no type inside a <form> defaults to SUBMIT. If Cancel ever loses its
        // type=button it will save the very edits it exists to discard.
        test("Cancel leaves without saving") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host = page(editingSeries(hasChanges = true), onEvent = { seen += it })

            button(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly listOf(SeriesEditUiEvent.CancelClicked)
        }

        test("an error is announced, and can be dismissed") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host = page(editingSeries(error = "That name is already taken."), onEvent = { seen += it })

            val alert = host.querySelector(".sed-err-t").shouldNotBeNull()
            alert.getAttribute("role") shouldBe "alert"
            alert.textContent shouldBe "That name is already taken."

            (host.querySelector(".sed-err-x") as HTMLButtonElement).click()
            awaitFrame()

            seen shouldContainExactly listOf(SeriesEditUiEvent.ErrorDismissed)
        }

        test("a page still loading draws nothing it does not know yet") {
            val host = page(editingSeries(isLoading = true))

            host.querySelector(".sed-skel").shouldNotBeNull()
            host.querySelector("form").shouldBeNull()
        }

        // ⛔ `/api/v1/series/{id}/cover`, never a book's. Series Detail deliberately shows the first
        // book's cover because a gradient beats a 404 there; here the question is whether THIS
        // series has artwork, and a borrowed image answers it wrongly for every series without any.
        test("a series with its own artwork shows that artwork") {
            val host = page(editingSeries(coverPath = "series/s-stormlight.jpg"))

            host.querySelector(".cover-art img")?.getAttribute("src") shouldBe "/api/v1/series/s-stormlight/cover"
        }

        test("a series with no artwork says so rather than borrowing a book's") {
            val host = page(editingSeries(coverPath = null))

            host.querySelector(".cover-art img").shouldBeNull()
            host.querySelector(".sed-cover-none")?.textContent shouldContain "No cover yet"
        }

        // ⛔ The ViewModel's CoverRemoved discards the STAGED pick — it never deletes artwork the
        // server holds. Offering it with nothing staged would promise a deletion nothing performs.
        test("nothing offers to undo a pick that was never made") {
            val host = page(editingSeries(coverPath = "series/s-stormlight.jpg", pendingCoverData = null))

            button(host, "Keep the current cover").shouldBeNull()
        }

        test("a staged pick can be put back, and says that is what it does") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host = page(editingSeries(pendingCoverData = byteArrayOf(1, 2, 3)), onEvent = { seen += it })

            button(host, "Keep the current cover").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly listOf(SeriesEditUiEvent.CoverRemoved)
        }

        test("an upload in flight cannot be started twice") {
            val host = page(editingSeries(isUploadingCover = true))

            (host.querySelector(".cover-pick") as HTMLButtonElement).hasAttribute("disabled") shouldBe true
        }

        test("the page says how many books the series holds") {
            page(editingSeries(bookCount = 1)).let {
                it.querySelector(".sed-hint")?.textContent shouldBe "1 book in this series."
            }
            page(editingSeries(bookCount = 5)).let {
                it.querySelector(".sed-hint")?.textContent shouldBe "5 books in this series."
            }
        }

        test("merging opens the picker") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host = page(editingSeries(), onEvent = { seen += it })

            button(host, "Merge into another series").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly listOf(SeriesEditUiEvent.MergeDialogOpened)
        }

        test("a merge in flight says so, and cannot be started twice") {
            val host = page(editingSeries(mergeInProgress = true))

            button(host, "Merging…").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        // ⛔ Both facts, not one. How many books move is what the decision costs; that it cannot be
        // undone is why the decision is worth pausing over.
        test("the picker says what the merge will cost and that it is final") {
            val host = page(editingSeries(bookCount = 5, mergeDialogVisible = true))

            val dialog = host.querySelector("dialog").shouldNotBeNull()
            dialog.textContent.shouldNotBeNull() shouldContain "5 books move across"
            host.querySelector(".sed-warn")?.textContent shouldBe "This cannot be undone."
        }

        test("a query that matches nothing says so, and says what was searched for") {
            val host = page(editingSeries(mergeDialogVisible = true, mergeQuery = "Elantris"))

            host.querySelector(".sed-none")?.textContent shouldBe "Nothing matched \"Elantris\"."
        }

        test("the picker reports what was typed into it") {
            val typed = mutableListOf<String>()
            val host = page(editingSeries(mergeDialogVisible = true), onMergeQuery = { typed += it })

            type(host, "sed-merge-query", "Mist")
            awaitFrame()

            typed shouldContainExactly listOf("Mist")
        }

        // ⛔ A capped list that says nothing reads as a complete one, and "it isn't in the list" is
        // how the wrong series gets picked. The ViewModel caps at MAX_MERGE_CANDIDATES.
        test("a full page of candidates admits there may be more") {
            val full = (1..MAX_MERGE_CANDIDATES).map { seriesCandidate(id = "s$it", displayName = "Series $it") }
            val host = page(editingSeries(mergeDialogVisible = true, mergeQuery = "S"), mergeCandidates = full)

            host.querySelector(".sed-trunc")?.textContent shouldBe
                "Showing the first $MAX_MERGE_CANDIDATES. Search to narrow them down."
        }

        test("a list that fits claims nothing about a longer one") {
            val some = (1..3).map { seriesCandidate(id = "s$it", displayName = "Series $it") }
            val host = page(editingSeries(mergeDialogVisible = true, mergeQuery = "S"), mergeCandidates = some)

            host.querySelector(".sed-trunc").shouldBeNull()
        }

        // ⛔ Select, then confirm. A one-press merge is an irreversible operation behind a single
        // mis-click, which is exactly what the two gestures exist to prevent.
        test("Merge is refused until a series is selected") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host =
                page(
                    editingSeries(mergeDialogVisible = true, mergeQuery = "Mist"),
                    mergeCandidates = listOf(seriesCandidate()),
                    onEvent = { seen += it },
                )

            val merge = dialogButton(host, "Merge").shouldNotBeNull()
            merge.hasAttribute("disabled") shouldBe true

            merge.click()
            awaitFrame()

            seen shouldContainExactly emptyList()
        }

        test("selecting a series then confirming folds this one into it") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host =
                page(
                    editingSeries(mergeDialogVisible = true, mergeQuery = "Mist"),
                    mergeCandidates = listOf(seriesCandidate(id = "s-mistborn", displayName = "Mistborn")),
                    onEvent = { seen += it },
                )

            val row = results(host).single()
            row.textContent shouldBe "Mistborn"
            row.getAttribute("aria-pressed") shouldBe "false"

            row.click()
            awaitFrame()

            // The selection is announced, not just tinted — colour alone is not a state.
            results(host).single().getAttribute("aria-pressed") shouldBe "true"
            dialogButton(host, "Merge").shouldNotBeNull().hasAttribute("disabled") shouldBe false
            seen shouldContainExactly emptyList()

            dialogButton(host, "Merge").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly listOf(SeriesEditUiEvent.MergeInto(SeriesId("s-mistborn")))
        }

        test("selecting one candidate deselects the other") {
            val host =
                page(
                    editingSeries(mergeDialogVisible = true, mergeQuery = "S"),
                    mergeCandidates =
                        listOf(
                            seriesCandidate(id = "s1", displayName = "Mistborn"),
                            seriesCandidate(id = "s2", displayName = "Elantris"),
                        ),
                )

            results(host)[0].click()
            awaitFrame()
            results(host)[1].click()
            awaitFrame()

            results(host).map { it.getAttribute("aria-pressed") } shouldContainExactly listOf("false", "true")
            // ⛔ And the highlight, not only the announcement. Sabotage proved a page that tinted
            // every row passed on `aria-pressed` alone — the two halves of the signal are drawn by
            // two different expressions, so asserting one leaves the other free to be wrong.
            results(host).map { it.classList.contains("on") } shouldContainExactly listOf(false, true)
        }

        test("dismissing the picker merges nothing") {
            val seen = mutableListOf<SeriesEditUiEvent>()
            val host =
                page(
                    editingSeries(mergeDialogVisible = true, mergeQuery = "Mist"),
                    mergeCandidates = listOf(seriesCandidate()),
                    onEvent = { seen += it },
                )

            results(host).single().click()
            awaitFrame()
            dialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly listOf(SeriesEditUiEvent.MergeDialogDismissed)
        }
    })
