package com.calypsan.listenup.web.features.contributoredit

import com.calypsan.listenup.client.presentation.contributoredit.ContributorCandidate
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiEvent
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiState
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.EventInit
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

internal fun candidate(
    id: String = "c-bachman",
    displayName: String = "Richard Bachman",
) = ContributorCandidate(id = ContributorId(id), displayName = displayName, bookCount = 0)

@Suppress("LongParameterList")
internal fun editingContributor(
    name: String = "Stephen King",
    description: String = "",
    website: String = "",
    birthDate: String = "",
    deathDate: String = "",
    aliases: List<String> = emptyList(),
    imagePath: String? = null,
    error: String? = null,
    isLoading: Boolean = false,
    isSaving: Boolean = false,
    isUploadingImage: Boolean = false,
    hasChanges: Boolean = false,
    mergeInProgress: Boolean = false,
    mergeDialogVisible: Boolean = false,
    mergeQuery: String = "",
    renameCollisionCandidate: ContributorCandidate? = null,
) = ContributorEditUiState(
    isLoading = isLoading,
    isSaving = isSaving,
    isUploadingImage = isUploadingImage,
    error = error,
    contributorId = "c-king",
    imagePath = imagePath,
    name = name,
    description = description,
    website = website,
    birthDate = birthDate,
    deathDate = deathDate,
    aliases = aliases,
    mergeInProgress = mergeInProgress,
    mergeDialogVisible = mergeDialogVisible,
    mergeQuery = mergeQuery,
    hasChanges = hasChanges,
    renameCollisionCandidate = renameCollisionCandidate,
)

private fun page(
    state: ContributorEditUiState,
    mergeCandidates: List<ContributorCandidate> = emptyList(),
    onEvent: (ContributorEditUiEvent) -> Unit = {},
    onMergeQuery: (String) -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        ContributorEditPage(
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
 * ⛔ Not [button]. "Split out" and "Cancel" each name a control on the page as well as one in the
 * dialog over it, and a document-wide lookup finds the page's — pressing the row button again
 * instead of confirming, and reading as a pass because nothing happened either way.
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

/**
 * Contributor Edit — the form over one person, and the three ways this page can destroy one.
 *
 * What these pin: the form reflects the ViewModel and nothing else; each field reports its own
 * change (a setter wired to the wrong input is the defect this shape invites); Save is honest about
 * whether there is anything to save; and — the reason this page is not just another form — merging,
 * splitting, and a colliding rename each ask their own question, because sharing one confirmation
 * across the three would be asking a question none of them are.
 *
 * The candidate book count is deliberately absent everywhere. `ContributorCandidate.bookCount` is a
 * placeholder the ViewModel always fills with `0`, so rendering it would tell every reader that
 * every contributor has no books.
 */
class ContributorEditPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the form shows the person it was given") {
            val host =
                page(
                    editingContributor(
                        name = "Ursula K. Le Guin",
                        description = "Wrote the Earthsea books.",
                        website = "https://ursulakleguin.com",
                        birthDate = "1929-10-21",
                        deathDate = "2018-01-22",
                    ),
                )

            input(host, "ced-name").value shouldBe "Ursula K. Le Guin"
            (host.querySelector("#ced-description") as HTMLTextAreaElement).value shouldBe "Wrote the Earthsea books."
            input(host, "ced-website").value shouldBe "https://ursulakleguin.com"
            input(host, "ced-birth").value shouldBe "1929-10-21"
            input(host, "ced-death").value shouldBe "2018-01-22"
        }

        // ⛔ `type=date` is the whole reason the ViewModel's ISO `YYYY-MM-DD` arrives well-formed.
        // A free-text field accepts "21st Oct 1929" and the save fails at the far end.
        test("the dates are date fields, so the browser produces the ISO the ViewModel wants") {
            val host = page(editingContributor())

            input(host, "ced-birth").getAttribute("type") shouldBe "date"
            input(host, "ced-death").getAttribute("type") shouldBe "date"
        }

        test("each field reports its own change") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host = page(editingContributor(), onEvent = { seen += it })

            // A distinct value per field, so a setter wired to the wrong input cannot read as right.
            type(host, "ced-name", "one")
            type(host, "ced-website", "two")
            type(host, "ced-birth", "1947-09-21")
            type(host, "ced-death", "1999-12-31")
            awaitFrame()

            seen shouldContainExactly
                listOf(
                    ContributorEditUiEvent.NameChanged("one"),
                    ContributorEditUiEvent.WebsiteChanged("two"),
                    ContributorEditUiEvent.BirthDateChanged("1947-09-21"),
                    ContributorEditUiEvent.DeathDateChanged("1999-12-31"),
                )
        }

        test("the biography reports its own change") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host = page(editingContributor(), onEvent = { seen += it })

            val area = host.querySelector("#ced-description") as HTMLTextAreaElement
            area.value = "Wrote a lot."
            area.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            seen shouldContainExactly listOf(ContributorEditUiEvent.DescriptionChanged("Wrote a lot."))
        }

        test("Save is disabled until something changes") {
            page(editingContributor(hasChanges = false)).let { saveButton(it).hasAttribute("disabled") shouldBe true }
        }

        test("Save is offered once something has changed") {
            page(editingContributor(hasChanges = true)).let { saveButton(it).hasAttribute("disabled") shouldBe false }
        }

        test("Save says so while it is in flight, and cannot be pressed twice") {
            val host = page(editingContributor(hasChanges = true, isSaving = true))

            saveButton(host).textContent shouldBe "Saving…"
            saveButton(host).hasAttribute("disabled") shouldBe true
        }

        test("submitting the form saves") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host = page(editingContributor(hasChanges = true), onEvent = { seen += it })

            saveButton(host).click()
            awaitFrame()

            seen shouldContainExactly listOf(ContributorEditUiEvent.Save)
        }

        // ⛔ A <button> with no type inside a <form> defaults to SUBMIT. If Cancel ever loses its
        // type=button it will save the very edits it exists to discard, and this is what notices.
        test("Cancel leaves without saving") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host = page(editingContributor(hasChanges = true), onEvent = { seen += it })

            button(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly listOf(ContributorEditUiEvent.Cancel)
        }

        test("an error is announced, and can be dismissed") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host = page(editingContributor(error = "That name is already taken."), onEvent = { seen += it })

            val alert = host.querySelector(".ced-err-t").shouldNotBeNull()
            alert.getAttribute("role") shouldBe "alert"
            alert.textContent shouldBe "That name is already taken."

            (host.querySelector(".ced-err-x") as HTMLButtonElement).click()
            awaitFrame()

            seen shouldContainExactly listOf(ContributorEditUiEvent.DismissError)
        }

        test("a contributor with no other names says so rather than showing an empty list") {
            val host = page(editingContributor(aliases = emptyList()))

            host.querySelector(".ced-aliases").shouldBeNull()
            host.querySelector(".ced-none")?.textContent shouldBe "No other names."
        }

        test("every alias is listed") {
            val host = page(editingContributor(aliases = listOf("Richard Bachman", "John Swithen")))

            host
                .querySelectorAll(".ced-alias-n")
                .asList()
                .map { it.textContent } shouldContainExactly listOf("Richard Bachman", "John Swithen")
        }

        // ⛔ Splitting an alias out is destructive enough to confirm, and the confirmation must name
        // the alias being split — one "Split out" press per row against a shared dialog that names
        // none of them is how the wrong name gets split.
        test("splitting an alias out asks first, and names the one being split") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host = page(editingContributor(aliases = listOf("Richard Bachman", "John Swithen")), onEvent = { seen += it })

            host
                .querySelectorAll("button[aria-label=\"Split John Swithen back out\"]")
                .asList()
                .also { it.size shouldBe 1 }
                .first()
                .let { (it as HTMLButtonElement).click() }
            awaitFrame()

            host
                .querySelector(".dlg-p")
                .shouldNotBeNull()
                .textContent
                .shouldNotBeNull() shouldContain "John Swithen"
            seen shouldContainExactly emptyList()

            dialogButton(host, "Split out").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly listOf(ContributorEditUiEvent.UnmergeAlias("John Swithen"))
        }

        test("dismissing the split confirmation splits nothing") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host = page(editingContributor(aliases = listOf("Richard Bachman")), onEvent = { seen += it })

            (host.querySelector("button[aria-label=\"Split Richard Bachman back out\"]") as HTMLButtonElement).click()
            awaitFrame()
            dialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly emptyList()
        }

        test("folding another contributor in opens the picker") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host = page(editingContributor(), onEvent = { seen += it })

            button(host, "Fold another contributor in").shouldNotBeNull().click()
            awaitFrame()

            seen shouldContainExactly listOf(ContributorEditUiEvent.MergeDialogOpened)
        }

        test("a merge in flight says so, and cannot be started twice") {
            val host = page(editingContributor(aliases = listOf("Richard Bachman"), mergeInProgress = true))

            val merge = button(host, "Merging…").shouldNotBeNull()
            merge.hasAttribute("disabled") shouldBe true
            (host.querySelector(".ced-split") as HTMLButtonElement).hasAttribute("disabled") shouldBe true
        }

        test("the picker asks for a name before it lists anybody") {
            val host = page(editingContributor(mergeDialogVisible = true, mergeQuery = ""))

            host.querySelector(".ced-results")?.textContent shouldBe "Type a name."
        }

        test("a query that matches nobody says so, and says what was searched for") {
            val host = page(editingContributor(mergeDialogVisible = true, mergeQuery = "Bachmann"))

            host.querySelector(".ced-results")?.textContent shouldBe "Nobody matched \"Bachmann\"."
        }

        test("the picker reports what was typed into it") {
            val typed = mutableListOf<String>()
            val host = page(editingContributor(mergeDialogVisible = true), onMergeQuery = { typed += it })

            type(host, "ced-merge-query", "Bach")
            awaitFrame()

            typed shouldContainExactly listOf("Bach")
        }

        // ⛔ Name only. `bookCount` is always 0, so a count here would read "0 books" beside every
        // candidate — a confident, wrong answer to the one question the picker exists to settle.
        test("a candidate is offered by name, and picking one folds it in") {
            val seen = mutableListOf<ContributorEditUiEvent>()
            val host =
                page(
                    editingContributor(mergeDialogVisible = true, mergeQuery = "Bach"),
                    mergeCandidates = listOf(candidate(id = "c-bachman", displayName = "Richard Bachman")),
                    onEvent = { seen += it },
                )

            val result = host.querySelector(".ced-result").shouldNotBeNull()
            result.textContent shouldBe "Richard Bachman"

            (result as HTMLButtonElement).click()
            awaitFrame()

            seen shouldContainExactly listOf(ContributorEditUiEvent.MergeInto(ContributorId("c-bachman")))
        }

        // ⛔ Three answers, not two. Dismissing is the absence of an answer and must emit the event
        // that keeps the rename held back — a dismissal that silently completed the rename would be
        // the page answering a question it asked on the reader's behalf.
        test("a colliding rename offers all three answers, and each says which was meant") {
            listOf(
                "Fold into Richard Bachman" to ContributorEditUiEvent.ConfirmMergeOnRename,
                "Keep separate" to ContributorEditUiEvent.KeepSeparateOnRename,
            ).forEach { (label, expected) ->
                val seen = mutableListOf<ContributorEditUiEvent>()
                val host =
                    page(
                        editingContributor(name = "Richard Bachman", renameCollisionCandidate = candidate()),
                        onEvent = { seen += it },
                    )

                dialogButton(host, label).shouldNotBeNull().click()
                awaitFrame()

                seen shouldContainExactly listOf(expected)
            }
        }

        test("the collision names the person already using the typed name") {
            val host =
                page(editingContributor(name = "Richard Bachman", renameCollisionCandidate = candidate()))

            val dialog = host.querySelector("dialog").shouldNotBeNull()
            dialog.textContent.shouldNotBeNull() shouldContain "There is already a Richard Bachman"
            dialog.textContent.shouldNotBeNull() shouldContain "Richard Bachman already exists."
        }

        test("a page still loading draws nothing it does not know yet") {
            val host = page(editingContributor(isLoading = true))

            host.querySelector(".ced-skel").shouldNotBeNull()
            host.querySelector("form").shouldBeNull()
        }

        test("an upload in flight says so, and cannot be started twice") {
            val host = page(editingContributor(isUploadingImage = true))

            button(host, "Uploading…").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        // A contributor with no photo is the normal case, so the monogram is a finished portrait
        // rather than a broken image — nothing should be asking the server for a 404.
        test("a contributor with no photo gets the monogram, not a broken image") {
            val host = page(editingContributor(imagePath = null))

            host.querySelector("img").shouldBeNull()
            host.querySelector(".ced-photo-none").shouldNotBeNull()
        }

        test("a contributor with a photo shows it") {
            val host = page(editingContributor(imagePath = "contributors/c-king.jpg"))

            host.querySelector("img")?.getAttribute("src") shouldBe "/api/v1/contributors/c-king/photo"
        }
    })
