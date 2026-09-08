package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
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
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

internal fun collection(
    id: String = "c1",
    name: String = "Bedtime",
    bookCount: Int = 4,
    isSystem: Boolean = false,
    isInbox: Boolean = false,
): Collection =
    Collection(
        id = id,
        name = name,
        ownerId = "u1",
        isInbox = isInbox,
        isSystem = isSystem,
        bookCount = bookCount,
        callerPermission = SharePermission.Write,
        isOwner = true,
    )

internal fun readyCollections(
    collections: List<Collection> = listOf(collection()),
    isCreating: Boolean = false,
    deletingCollectionId: String? = null,
    error: String? = null,
): AdminCollectionsUiState.Ready =
    AdminCollectionsUiState.Ready(
        collections = collections,
        isCreating = isCreating,
        deletingCollectionId = deletingCollectionId,
        error = error,
    )

private fun page(
    state: AdminCollectionsUiState,
    onCreate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onOpenCollection: (String) -> Unit = {},
    onOpenAdmin: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        CollectionsPage(
            state = state,
            onCreate = onCreate,
            onDelete = onDelete,
            onClearError = onClearError,
            onOpenCollection = onOpenCollection,
            onOpenAdmin = onOpenAdmin,
        )
    }
    return host
}

private fun labelled(
    host: HTMLElement,
    label: String,
) = host
    .querySelectorAll("button[aria-label]")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.getAttribute("aria-label") == label }

private fun dialogButton(
    host: HTMLElement,
    label: String,
) = host
    .querySelectorAll("dialog.dlg button")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.textContent?.trim() == label }

/**
 * The collections list.
 *
 * What these pin: a system collection shows a padlock instead of a Delete, because the server
 * refuses to delete it and a row simply missing its button reads as a rendering fault; deleting
 * says the books survive, since otherwise it reads as "delete these forty books"; and the name is
 * the way in, so a list that cannot be opened would be a dead end.
 */
class CollectionsPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("a collection shows its name and how many books are in it") {
            val host = page(readyCollections(listOf(collection(name = "Bedtime", bookCount = 4))))

            host.querySelector(".coll-name")?.textContent shouldBe "Bedtime"
            host.querySelector(".coll-books")?.textContent shouldBe "4 books"
        }

        test("one book is one book") {
            val host = page(readyCollections(listOf(collection(bookCount = 1))))

            host.querySelector(".coll-books")?.textContent shouldBe "1 book"
        }

        test("opening a collection reports which one") {
            val opened = mutableListOf<String>()
            val host = page(readyCollections(listOf(collection(id = "c7"))), onOpenCollection = { opened += it })

            (host.querySelector(".coll-open") as HTMLElement).click()
            awaitFrame()

            opened shouldContainExactly listOf("c7")
        }

        // ⛔ The server refuses to delete its own collections. A padlock says a person decided
        // this; a missing button says the page failed to render.
        test("a system collection is locked rather than deletable") {
            val host = page(readyCollections(listOf(collection(name = "All books", isSystem = true))))

            host.querySelector(".coll-lock").shouldNotBeNull()
            labelled(host, "Delete All books").shouldBeNull()
        }

        test("an ordinary collection offers a delete") {
            val host = page(readyCollections(listOf(collection(name = "Bedtime"))))

            host.querySelector(".coll-lock").shouldBeNull()
            labelled(host, "Delete Bedtime").shouldNotBeNull()
        }

        test("deleting asks first, and says the books survive") {
            var deleted = 0
            val host = page(readyCollections(listOf(collection(name = "Bedtime"))), onDelete = { deleted++ })

            labelled(host, "Delete Bedtime").shouldNotBeNull().click()
            awaitFrame()

            val text = host.querySelector("dialog.dlg")?.textContent.orEmpty()
            text shouldContain "Bedtime"
            text shouldContain "The books stay in the library"
            deleted shouldBe 0
        }

        test("confirming deletes the collection that was asked about") {
            val deleted = mutableListOf<String>()
            val host = page(readyCollections(listOf(collection(id = "c7"))), onDelete = { deleted += it })

            labelled(host, "Delete Bedtime").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, "Delete").shouldNotBeNull().click()
            awaitFrame()

            deleted shouldContainExactly listOf("c7")
        }

        test("cancelling deletes nothing") {
            val deleted = mutableListOf<String>()
            val host = page(readyCollections(), onDelete = { deleted += it })

            labelled(host, "Delete Bedtime").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            deleted shouldContainExactly emptyList()
        }

        test("creating reports the name") {
            val created = mutableListOf<String>()
            val host = page(readyCollections(), onCreate = { created += it })

            (host.querySelector(".coll-new") as HTMLElement).click()
            awaitFrame()
            val field = host.querySelector("#cat-name") as HTMLInputElement
            field.value = "Long drives"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            dialogButton(host, "Create").shouldNotBeNull().click()
            awaitFrame()

            created shouldContainExactly listOf("Long drives")
        }

        test("a create in flight says so and cannot be started again") {
            val host = page(readyCollections(isCreating = true))

            val button = host.querySelector(".coll-new") as HTMLElement
            button.textContent shouldBe "Creating…"
            button.hasAttribute("disabled") shouldBe true
        }

        test("a delete in flight disables that row's button") {
            val host = page(readyCollections(listOf(collection(id = "c7")), deletingCollectionId = "c7"))

            labelled(host, "Delete Bedtime").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("a failure is reported, announced, and can be dismissed") {
            var cleared = 0
            val host = page(readyCollections(error = "No library available"), onClearError = { cleared++ })

            host.querySelector(".coll-err-t").shouldNotBeNull().getAttribute("role") shouldBe "alert"
            (host.querySelector(".coll-err-x") as HTMLElement).click()
            awaitFrame()

            cleared shouldBe 1
        }

        test("an empty list says what a collection is for") {
            val host = page(readyCollections(collections = emptyList()))

            host.querySelector(".empty")?.textContent.orEmpty() shouldContain "group of books"
        }

        test("a loading list draws a skeleton") {
            val host = page(AdminCollectionsUiState.Loading)

            host.querySelector(".coll-skel").shouldNotBeNull()
            host.querySelector(".coll-row").shouldBeNull()
        }

        test("a list that cannot be loaded explains itself") {
            val host = page(AdminCollectionsUiState.Error("Server said no."))

            host.textContent.orEmpty() shouldContain "Server said no."
            host.querySelector(".coll-row").shouldBeNull()
        }
    })
