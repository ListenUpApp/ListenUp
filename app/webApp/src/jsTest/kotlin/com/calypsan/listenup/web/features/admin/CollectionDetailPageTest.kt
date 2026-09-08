package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.CollectionBookItem
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.CollectionShareItem
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

private fun book(
    id: String = "b1",
    title: String = "The Way of Kings",
    author: String? = "Brandon Sanderson",
) = CollectionBookItem(id = id, title = title, author = author, coverPath = null, durationMs = 0L)

internal fun shareFixture(
    userId: String = "u2",
    displayName: String = "Ada",
    permission: String = "READ",
) = CollectionShareItem(id = "s-$userId", userId = userId, displayName = displayName, permission = permission)

internal fun personFixture(
    id: String = "u2",
    displayName: String? = "Ada",
) = AdminUserInfo(
    id = id,
    email = "$id@example.com",
    displayName = displayName,
    firstName = null,
    lastName = null,
    isRoot = false,
    role = "member",
    status = "active",
    createdAt = "2026-01-01T00:00:00Z",
)

@Suppress("LongParameterList")
internal fun readyDetail(
    name: String = "Bedtime",
    isSystem: Boolean = false,
    editedName: String = name,
    isSaving: Boolean = false,
    books: List<CollectionBookItem> = listOf(book()),
    removingBookId: String? = null,
    error: String? = null,
    shares: List<CollectionShareItem> = emptyList(),
    showAddMemberSheet: Boolean = false,
    availableUsers: List<AdminUserInfo> = emptyList(),
    isLoadingUsers: Boolean = false,
    showAddBooks: Boolean = false,
    bookQuery: String = "",
    bookResults: List<SearchHit> = emptyList(),
    isSearchingBooks: Boolean = false,
): AdminCollectionDetailUiState.Ready =
    AdminCollectionDetailUiState.Ready(
        collection = collection(name = name, isSystem = isSystem),
        editedName = editedName,
        isSaving = isSaving,
        books = books,
        removingBookId = removingBookId,
        error = error,
        shares = shares,
        showAddMemberSheet = showAddMemberSheet,
        isLoadingUsers = isLoadingUsers,
        availableUsers = availableUsers,
        showAddBooks = showAddBooks,
        bookQuery = bookQuery,
        bookResults = bookResults,
        isSearchingBooks = isSearchingBooks,
    )

@Suppress("LongParameterList")
private fun page(
    state: AdminCollectionDetailUiState,
    onNameChange: (String) -> Unit = {},
    onSaveName: () -> Unit = {},
    onRemoveBook: (String) -> Unit = {},
    onOpenAddBooks: () -> Unit = {},
    onCloseAddBooks: () -> Unit = {},
    onBookQuery: (String) -> Unit = {},
    onAddBook: (String) -> Unit = {},
    onShowAddMember: () -> Unit = {},
    onHideAddMember: () -> Unit = {},
    onShare: (String) -> Unit = {},
    onRevokeShare: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        CollectionDetailPage(
            state = state,
            onNameChange = onNameChange,
            onSaveName = onSaveName,
            onRemoveBook = onRemoveBook,
            onOpenAddBooks = onOpenAddBooks,
            onCloseAddBooks = onCloseAddBooks,
            onBookQuery = onBookQuery,
            onAddBook = onAddBook,
            onShowAddMember = onShowAddMember,
            onHideAddMember = onHideAddMember,
            onShare = onShare,
            onRevokeShare = onRevokeShare,
            onClearError = onClearError,
            onOpenCollections = onOpenCollections,
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

private fun text(
    host: HTMLElement,
    selector: String,
) = host.querySelectorAll(selector).asList().mapNotNull { (it as HTMLElement).textContent }

/**
 * One collection.
 *
 * What these pin: a system collection is genuinely read-only rather than merely un-saveable — the
 * name field is disabled and Add books is absent, because offering a control the server will reject
 * teaches the reader the app lies; the two panels are ViewModel state, so the page opens them by
 * asking rather than by remembering; and the search panel tells "nothing typed" apart from "nothing
 * found", which are different answers to different questions.
 */
class CollectionDetailPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the collection's name is the heading and the field") {
            val host = page(readyDetail(name = "Bedtime"))

            host.querySelector(".cdet-title")?.textContent shouldBe "Bedtime"
            (host.querySelector("#cdet-name") as HTMLInputElement).value shouldBe "Bedtime"
        }

        test("typing a new name reports it") {
            val seen = mutableListOf<String>()
            val host = page(readyDetail(), onNameChange = { seen += it })

            val field = host.querySelector("#cdet-name") as HTMLInputElement
            field.value = "Long drives"
            field.dispatchEvent(
                org.w3c.dom.events
                    .Event("input", org.w3c.dom.EventInit(bubbles = true)),
            )
            awaitFrame()

            seen shouldContainExactly listOf("Long drives")
        }

        // Absent rather than greyed: a permanently disabled Save on a screen you did not come here
        // to edit is furniture.
        test("Save appears only once the name has changed") {
            val clean = page(readyDetail(name = "Bedtime", editedName = "Bedtime"))
            val dirty = page(readyDetail(name = "Bedtime", editedName = "Long drives"))

            clean.querySelector(".edit-actions").shouldBeNull()
            dirty.querySelector(".edit-actions").shouldNotBeNull()
        }

        test("saving reports it, and says so while it is in flight") {
            var saved = 0
            val host = page(readyDetail(editedName = "Long drives"), onSaveName = { saved++ })
            val saving = page(readyDetail(editedName = "Long drives", isSaving = true))

            (host.querySelector(".edit-actions button") as HTMLElement).click()
            awaitFrame()

            saved shouldBe 1
            (saving.querySelector(".edit-actions button") as HTMLElement).textContent shouldBe "Saving…"
        }

        // ⛔ The three guards Compose applies. A system collection belongs to the server: it will
        // refuse a rename and refuse a book change, so neither is offered.
        test("a system collection cannot be renamed") {
            val host = page(readyDetail(name = "All books", isSystem = true))

            (host.querySelector("#cdet-name") as HTMLInputElement).hasAttribute("disabled") shouldBe true
            host.textContent.orEmpty() shouldContain "The server manages this collection"
        }

        test("a system collection offers no way to change its books") {
            val host = page(readyDetail(isSystem = true, books = listOf(book(title = "Elantris"))))

            host.querySelector(".cdet-add").shouldBeNull()
            labelled(host, "Remove Elantris from this collection").shouldBeNull()
        }

        test("a system collection still shows the name field, and its Save stays away") {
            val host = page(readyDetail(name = "All books", isSystem = true, editedName = "Something else"))

            host.querySelector("#cdet-name").shouldNotBeNull()
            host.querySelector(".edit-actions").shouldBeNull()
        }

        test("an ordinary collection can have its books changed") {
            val host = page(readyDetail(books = listOf(book(title = "Elantris"))))

            host.querySelector(".cdet-add").shouldNotBeNull()
            labelled(host, "Remove Elantris from this collection").shouldNotBeNull()
        }

        test("a book shows its title and who wrote it") {
            val host = page(readyDetail(books = listOf(book(title = "Elantris", author = "Brandon Sanderson"))))

            host.querySelector(".cdet-book-title")?.textContent shouldBe "Elantris"
            host.querySelector(".cdet-book-by")?.textContent shouldBe "Brandon Sanderson"
        }

        test("a book with no author shows no byline") {
            val host = page(readyDetail(books = listOf(book(author = null))))

            host.querySelector(".cdet-book-by").shouldBeNull()
        }

        test("removing a book reports which one") {
            val removed = mutableListOf<String>()
            val host =
                page(
                    readyDetail(books = listOf(book(id = "b7", title = "Elantris"))),
                    onRemoveBook = { removed += it },
                )

            labelled(host, "Remove Elantris from this collection").shouldNotBeNull().click()
            awaitFrame()

            removed shouldContainExactly listOf("b7")
        }

        test("a removal in flight disables that book's button") {
            val host = page(readyDetail(books = listOf(book(id = "b7", title = "Elantris")), removingBookId = "b7"))

            labelled(host, "Remove Elantris from this collection")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
        }

        test("an empty collection says so") {
            val host = page(readyDetail(books = emptyList()))

            host.textContent.orEmpty() shouldContain "Nothing in this collection yet"
        }

        test("Add books asks the ViewModel to open the panel") {
            var opened = 0
            val host = page(readyDetail(), onOpenAddBooks = { opened++ })

            (host.querySelector(".cdet-add") as HTMLElement).click()
            awaitFrame()

            opened shouldBe 1
            // The panel is ViewModel state, so the page must NOT open it on its own.
            host.querySelector("dialog.dlg").shouldBeNull()
        }

        // Two different nothings: a reader who has typed a title needs to know the search ran.
        test("the search panel tells nothing-typed apart from nothing-found") {
            val idle = page(readyDetail(showAddBooks = true, bookQuery = ""))
            val empty = page(readyDetail(showAddBooks = true, bookQuery = "zzz", bookResults = emptyList()))
            val busy = page(readyDetail(showAddBooks = true, bookQuery = "zzz", isSearchingBooks = true))

            idle.querySelector("dialog.dlg")?.textContent.orEmpty() shouldContain "Type a title or an author"
            empty.querySelector("dialog.dlg")?.textContent.orEmpty() shouldContain "Nothing matched"
            busy.querySelector("dialog.dlg")?.textContent.orEmpty() shouldContain "Searching…"
        }

        test("typing in the search panel reports the query") {
            val seen = mutableListOf<String>()
            val host = page(readyDetail(showAddBooks = true), onBookQuery = { seen += it })

            val field = host.querySelector("#cdet-book-query") as HTMLInputElement
            field.value = "kings"
            field.dispatchEvent(
                org.w3c.dom.events
                    .Event("input", org.w3c.dom.EventInit(bubbles = true)),
            )
            awaitFrame()

            seen shouldContainExactly listOf("kings")
        }

        test("picking a result adds that book") {
            val added = mutableListOf<String>()
            val host =
                page(
                    readyDetail(
                        showAddBooks = true,
                        bookQuery = "kings",
                        bookResults = listOf(SearchHit(id = "b9", type = SearchHitType.BOOK, name = "The Way of Kings")),
                    ),
                    onAddBook = { added += it },
                )

            (host.querySelector(".cdet-result") as HTMLElement).click()
            awaitFrame()

            added shouldContainExactly listOf("b9")
        }

        test("a collection shared with nobody says only that") {
            val host = page(readyDetail(shares = emptyList()))

            host.textContent.orEmpty() shouldContain "Not shared with anyone yet"
        }

        test("a share shows who has it and what they can do") {
            val host = page(readyDetail(shares = listOf(shareFixture(displayName = "Ada", permission = "READ"))))

            host.querySelector(".cdet-share-n")?.textContent shouldBe "Ada"
            host.querySelector(".cdet-share-p")?.textContent shouldBe "read"
        }

        test("revoking a share reports the user, not the share row") {
            val revoked = mutableListOf<String>()
            val host =
                page(
                    readyDetail(shares = listOf(shareFixture(userId = "u9", displayName = "Ada"))),
                    onRevokeShare = { revoked += it },
                )

            labelled(host, "Stop sharing with Ada").shouldNotBeNull().click()
            awaitFrame()

            revoked shouldContainExactly listOf("u9")
        }

        // Offering someone who already has it would produce a duplicate share the server refuses.
        test("the picker does not offer someone who already has it") {
            val host =
                page(
                    readyDetail(
                        showAddMemberSheet = true,
                        availableUsers =
                            listOf(
                                personFixture(id = "u2", displayName = "Ada"),
                                personFixture(id = "u3", displayName = "Grace"),
                            ),
                        shares = listOf(shareFixture(userId = "u2", displayName = "Ada")),
                    ),
                )

            text(host, ".cdet-person-n") shouldContainExactly listOf("Grace")
        }

        test("the picker says which kind of nothing it has") {
            val nobody = page(readyDetail(showAddMemberSheet = true, availableUsers = emptyList()))
            val allShared =
                page(
                    readyDetail(
                        showAddMemberSheet = true,
                        availableUsers = listOf(personFixture(id = "u2")),
                        shares = listOf(shareFixture(userId = "u2")),
                    ),
                )

            nobody.querySelector("dialog.dlg")?.textContent.orEmpty() shouldContain "nobody else on this server"
            allShared.querySelector("dialog.dlg")?.textContent.orEmpty() shouldContain "already has this collection"
        }

        test("picking a person shares with them") {
            val shared = mutableListOf<String>()
            val host =
                page(
                    readyDetail(showAddMemberSheet = true, availableUsers = listOf(personFixture(id = "u9"))),
                    onShare = { shared += it },
                )

            (host.querySelector(".cdet-person") as HTMLElement).click()
            awaitFrame()

            shared shouldContainExactly listOf("u9")
        }

        test("a failure is reported, announced, and can be dismissed") {
            var cleared = 0
            val host = page(readyDetail(error = "Could not remove the book"), onClearError = { cleared++ })

            host.querySelector(".coll-err-t").shouldNotBeNull().getAttribute("role") shouldBe "alert"
            (host.querySelector(".coll-err-x") as HTMLElement).click()
            awaitFrame()

            cleared shouldBe 1
        }

        test("the way back to the list is offered, and fires") {
            var back = 0
            val host = page(readyDetail(), onOpenCollections = { back++ })

            (host.querySelector(".cdet-back") as HTMLElement).click()
            awaitFrame()

            back shouldBe 1
        }

        test("a loading collection draws a skeleton") {
            val host = page(AdminCollectionDetailUiState.Loading)

            host.querySelector(".cdet-skel").shouldNotBeNull()
            host.querySelector("#cdet-name").shouldBeNull()
        }

        test("a collection that cannot be loaded explains itself") {
            val host = page(AdminCollectionDetailUiState.Error("No such collection."))

            host.textContent.orEmpty() shouldContain "No such collection."
            host.querySelector("#cdet-name").shouldBeNull()
        }
    })
