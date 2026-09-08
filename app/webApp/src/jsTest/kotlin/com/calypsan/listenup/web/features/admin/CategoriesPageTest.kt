package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.GenreTreeNode
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
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

internal fun genre(
    id: String = "g1",
    name: String = "Fantasy",
    path: String = "/fantasy",
    bookCount: Int = 0,
): Genre = Genre(id = id, name = name, slug = name.lowercase(), path = path, bookCount = bookCount)

internal fun node(
    genre: Genre,
    depth: Int = 0,
    children: List<GenreTreeNode> = emptyList(),
): GenreTreeNode = GenreTreeNode(genre = genre, children = children, depth = depth)

internal fun readyCategories(
    tree: List<GenreTreeNode> = listOf(node(genre())),
    genres: List<Genre>? = null,
    expandedIds: Set<String> = emptySet(),
    totalBookCount: Int = 0,
    isSaving: Boolean = false,
    error: com.calypsan.listenup.api.error.AppError? = null,
): AdminCategoriesUiState.Ready {
    fun flatten(nodes: List<GenreTreeNode>): List<Genre> = nodes.flatMap { listOf(it.genre) + flatten(it.children) }
    return AdminCategoriesUiState.Ready(
        isSaving = isSaving,
        genres = genres ?: flatten(tree),
        tree = tree,
        expandedIds = expandedIds,
        totalBookCount = totalBookCount,
        error = error,
    )
}

@Suppress("LongParameterList")
private fun page(
    state: AdminCategoriesUiState,
    onToggleExpanded: (String) -> Unit = {},
    onExpandAll: () -> Unit = {},
    onCollapseAll: () -> Unit = {},
    onCreate: (String, String?) -> Unit = { _, _ -> },
    onRename: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onMove: (String, String?) -> Unit = { _, _ -> },
    onMerge: (String, String) -> Unit = { _, _ -> },
    onClearError: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        CategoriesPage(
            state = state,
            onToggleExpanded = onToggleExpanded,
            onExpandAll = onExpandAll,
            onCollapseAll = onCollapseAll,
            onCreate = onCreate,
            onRename = onRename,
            onDelete = onDelete,
            onMove = onMove,
            onMerge = onMerge,
            onClearError = onClearError,
            onOpenAdmin = onOpenAdmin,
        )
    }
    return host
}

private fun rows(host: HTMLElement) = host.querySelectorAll(".cat-row").asList().filterIsInstance<HTMLElement>()

private fun names(host: HTMLElement) = host.querySelectorAll(".cat-name").asList().mapNotNull { (it as HTMLElement).textContent }

/** The action strip is labelled per genre, so a spec addresses a button the way a reader hears it. */
private fun action(
    host: HTMLElement,
    label: String,
): HTMLElement? =
    host
        .querySelectorAll("button[aria-label]")
        .asList()
        .filterIsInstance<HTMLElement>()
        .firstOrNull { it.getAttribute("aria-label") == label }

private fun dialogButton(
    host: HTMLElement,
    label: String,
): HTMLElement? =
    host
        .querySelectorAll("dialog.dlg button")
        .asList()
        .filterIsInstance<HTMLElement>()
        .firstOrNull { it.textContent?.trim() == label }

private fun typeInto(
    host: HTMLElement,
    selector: String,
    text: String,
) {
    val field = host.querySelector(selector) as HTMLInputElement
    field.value = text
    field.dispatchEvent(Event("input", EventInit(bubbles = true)))
}

private fun choose(
    host: HTMLElement,
    selector: String,
    value: String,
) {
    val select = host.querySelector(selector) as HTMLSelectElement
    select.value = value
    select.dispatchEvent(Event("change", EventInit(bubbles = true)))
}

// A two-level tree used by most specs: Fiction > Fantasy, plus a top-level Non-fiction.
private val fantasy = genre(id = "g2", name = "Fantasy", path = "/fiction/fantasy", bookCount = 3)
private val fiction = genre(id = "g1", name = "Fiction", path = "/fiction", bookCount = 10)
private val nonFiction = genre(id = "g3", name = "Non-fiction", path = "/non-fiction")

private fun twoLevelTree() =
    listOf(
        node(fiction, children = listOf(node(fantasy, depth = 1))),
        node(nonFiction),
    )

/**
 * The genre tree.
 *
 * What these pin: a collapsed genre's children are absent rather than hidden, the tree announces
 * itself as a tree with real levels, every action names the genre it acts on, and the two
 * restructuring operations refuse the shapes that would corrupt the tree — a move under one's own
 * descendant, and a merge into oneself.
 */
class CategoriesPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("a collapsed genre's children are not in the document at all") {
            val host = page(readyCategories(tree = twoLevelTree()))

            names(host) shouldContainExactly listOf("Fiction", "Non-fiction")
        }

        test("expanding shows the children") {
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")))

            names(host) shouldContainExactly listOf("Fiction", "Fantasy", "Non-fiction")
        }

        test("the tree says it is a tree, and says how deep each row sits") {
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")))

            host.querySelector("[role=tree]").shouldNotBeNull()
            rows(host).map { it.getAttribute("aria-level") } shouldContainExactly listOf("1", "2", "1")
        }

        // ⛔ `aria-expanded` on a leaf tells a screen reader there is something to open. Only a
        // genre that HAS children may carry it.
        test("only a genre with children claims to be expandable") {
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")))

            rows(host).map { it.getAttribute("aria-expanded") } shouldContainExactly listOf("true", null, null)
        }

        test("the twisty reports which genre was toggled") {
            val toggled = mutableListOf<String>()
            val host = page(readyCategories(tree = twoLevelTree()), onToggleExpanded = { toggled += it })

            action(host, "Expand Fiction").shouldNotBeNull().click()
            awaitFrame()

            toggled shouldContainExactly listOf("g1")
        }

        test("Expand all and Collapse all each fire their own thing") {
            var expanded = 0
            var collapsed = 0
            val host =
                page(
                    readyCategories(tree = twoLevelTree()),
                    onExpandAll = { expanded++ },
                    onCollapseAll = { collapsed++ },
                )

            host.querySelectorAll(".cat-bar-b").asList().filterIsInstance<HTMLElement>().let { buttons ->
                buttons.first { it.textContent?.trim() == "Expand all" }.click()
                buttons.first { it.textContent?.trim() == "Collapse all" }.click()
            }
            awaitFrame()

            expanded shouldBe 1
            collapsed shouldBe 1
        }

        // Five identical "Rename" labels down a tree is what a screen reader would otherwise
        // announce, with nothing to tell them apart.
        test("every action names the genre it acts on") {
            val host = page(readyCategories(tree = twoLevelTree()))

            action(host, "Rename Fiction").shouldNotBeNull()
            action(host, "Rename Non-fiction").shouldNotBeNull()
            action(host, "Delete Fiction").shouldNotBeNull()
            action(host, "Move Fiction").shouldNotBeNull()
            action(host, "Merge Fiction into another genre").shouldNotBeNull()
            action(host, "Add a genre under Fiction").shouldNotBeNull()
        }

        test("creating a top-level genre reports no parent") {
            val created = mutableListOf<Pair<String, String?>>()
            val host = page(readyCategories(tree = twoLevelTree()), onCreate = { n, p -> created += n to p })

            host
                .querySelectorAll(".cat-bar-b")
                .asList()
                .filterIsInstance<HTMLElement>()
                .first { it.textContent?.trim() == "New genre" }
                .click()
            awaitFrame()
            typeInto(host, "#cat-name", "Poetry")
            awaitFrame()
            dialogButton(host, "Create").shouldNotBeNull().click()
            awaitFrame()

            created shouldContainExactly listOf("Poetry" to null)
        }

        test("creating from a row reports that row as the parent") {
            val created = mutableListOf<Pair<String, String?>>()
            val host = page(readyCategories(tree = twoLevelTree()), onCreate = { n, p -> created += n to p })

            action(host, "Add a genre under Fiction").shouldNotBeNull().click()
            awaitFrame()
            typeInto(host, "#cat-name", "Sci-fi")
            awaitFrame()
            dialogButton(host, "Create").shouldNotBeNull().click()
            awaitFrame()

            created shouldContainExactly listOf("Sci-fi" to "g1")
        }

        // A genre with no name is not a genre, and the server's refusal would arrive as a toast
        // over a dialog still holding the same empty field.
        test("a nameless genre cannot be created") {
            var created = 0
            val host = page(readyCategories(tree = twoLevelTree()), onCreate = { _, _ -> created++ })

            host
                .querySelectorAll(".cat-bar-b")
                .asList()
                .filterIsInstance<HTMLElement>()
                .first { it.textContent?.trim() == "New genre" }
                .click()
            awaitFrame()
            typeInto(host, "#cat-name", "   ")
            awaitFrame()

            dialogButton(host, "Create").shouldNotBeNull().hasAttribute("disabled") shouldBe true
            created shouldBe 0
        }

        test("rename opens on the current name and reports the new one") {
            val renamed = mutableListOf<Pair<String, String>>()
            val host = page(readyCategories(tree = twoLevelTree()), onRename = { id, n -> renamed += id to n })

            action(host, "Rename Fiction").shouldNotBeNull().click()
            awaitFrame()
            (host.querySelector("#cat-name") as HTMLInputElement).value shouldBe "Fiction"
            typeInto(host, "#cat-name", "Stories")
            awaitFrame()
            dialogButton(host, "Rename").shouldNotBeNull().click()
            awaitFrame()

            renamed shouldContainExactly listOf("g1" to "Stories")
        }

        // The count is the whole decision: deleting an empty genre is housekeeping, deleting one
        // with ten books in it is not.
        test("deleting says how many books are affected, and that the files are safe") {
            val host = page(readyCategories(tree = twoLevelTree()))

            action(host, "Delete Fiction").shouldNotBeNull().click()
            awaitFrame()

            val text = host.querySelector("dialog.dlg")?.textContent.orEmpty()
            text shouldContain "10 books"
            text shouldContain "Nothing on disk is deleted"
        }

        test("deleting an empty genre says so instead of counting to zero") {
            val host = page(readyCategories(tree = twoLevelTree()))

            action(host, "Delete Non-fiction").shouldNotBeNull().click()
            awaitFrame()

            host.querySelector("dialog.dlg")?.textContent.orEmpty() shouldContain "has no books in it"
        }

        test("confirming a delete deletes; cancelling does not") {
            val deleted = mutableListOf<String>()
            val host = page(readyCategories(tree = twoLevelTree()), onDelete = { deleted += it })

            action(host, "Delete Fiction").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            deleted shouldContainExactly emptyList()

            action(host, "Delete Fiction").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, "Delete").shouldNotBeNull().click()
            awaitFrame()

            deleted shouldContainExactly listOf("g1")
        }

        // ⛔ Moving a genre under its own descendant makes a cycle. The rule lives in commonMain
        // (`genreMoveCandidates`) and both native clients read it; this proves web does too rather
        // than holding a second opinion about the shape of the tree.
        test("a genre cannot be moved under its own descendant") {
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")))

            action(host, "Move Fiction").shouldNotBeNull().click()
            awaitFrame()

            val options =
                host
                    .querySelectorAll("#cat-move-target option")
                    .asList()
                    .mapNotNull { (it as HTMLElement).textContent }
            // Top level and Non-fiction are legal; Fiction itself and its child Fantasy are not.
            options shouldContainExactly listOf("Top level", "Non-fiction")
        }

        test("moving to the top level reports no parent") {
            val moved = mutableListOf<Pair<String, String?>>()
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")), onMove = { id, p -> moved += id to p })

            action(host, "Move Fantasy").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, "Move").shouldNotBeNull().click()
            awaitFrame()

            moved shouldContainExactly listOf("g2" to null)
        }

        test("moving under a genre reports that genre") {
            val moved = mutableListOf<Pair<String, String?>>()
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")), onMove = { id, p -> moved += id to p })

            action(host, "Move Fantasy").shouldNotBeNull().click()
            awaitFrame()
            choose(host, "#cat-move-target", "g3")
            awaitFrame()
            dialogButton(host, "Move").shouldNotBeNull().click()
            awaitFrame()

            moved shouldContainExactly listOf("g2" to "g3")
        }

        test("a genre cannot be merged into itself") {
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")))

            action(host, "Merge Fiction into another genre").shouldNotBeNull().click()
            awaitFrame()

            val options =
                host
                    .querySelectorAll("#cat-merge-target option")
                    .asList()
                    .mapNotNull { (it as HTMLElement).textContent }
            options shouldContainExactly listOf("Fiction > Fantasy", "Non-fiction")
        }

        test("merging reports the source and the target, in that order") {
            val merged = mutableListOf<Pair<String, String>>()
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")), onMerge = { s, t -> merged += s to t })

            action(host, "Merge Fantasy into another genre").shouldNotBeNull().click()
            awaitFrame()
            choose(host, "#cat-merge-target", "g3")
            awaitFrame()
            dialogButton(host, "Merge").shouldNotBeNull().click()
            awaitFrame()

            merged shouldContainExactly listOf("g2" to "g3")
        }

        test("merging says the source stops existing") {
            val host = page(readyCategories(tree = twoLevelTree(), expandedIds = setOf("g1")))

            action(host, "Merge Fantasy into another genre").shouldNotBeNull().click()
            awaitFrame()

            host.querySelector("dialog.dlg")?.textContent.orEmpty() shouldContain "stops existing"
        }

        // Two genres can share a name at different points in the tree, so the bare name is not
        // enough to pick by.
        test("a picker shows where each genre sits, not just its name") {
            val host = page(readyCategories(tree = twoLevelTree()))

            action(host, "Merge Non-fiction into another genre").shouldNotBeNull().click()
            awaitFrame()

            host.querySelector("#cat-merge-target")?.textContent.orEmpty() shouldContain "Fiction > Fantasy"
        }

        test("a save in flight disables every action that would start another") {
            val host = page(readyCategories(tree = twoLevelTree(), isSaving = true))

            action(host, "Rename Fiction").shouldNotBeNull().hasAttribute("disabled") shouldBe true
        }

        test("a failed change is reported, announced, and can be dismissed") {
            var cleared = 0
            val host =
                page(
                    readyCategories(tree = twoLevelTree(), error = InternalError(debugInfo = "boom")),
                    onClearError = { cleared++ },
                )

            host.querySelector(".cat-err-t").shouldNotBeNull().getAttribute("role") shouldBe "alert"
            (host.querySelector(".cat-err-x") as HTMLElement).click()
            awaitFrame()

            cleared shouldBe 1
        }

        test("an empty tree says so rather than showing nothing") {
            val host = page(readyCategories(tree = emptyList(), genres = emptyList()))

            host.querySelector(".empty")?.textContent.orEmpty() shouldContain "No genres yet"
        }

        test("the summary counts genres and books") {
            val host = page(readyCategories(tree = twoLevelTree(), totalBookCount = 13))

            host.querySelector(".cat-count")?.textContent shouldBe "3 genres · 13 books"
        }

        test("a loading tree draws a skeleton rather than an empty list") {
            val host = page(AdminCategoriesUiState.Loading)

            host.querySelector(".cat-skel").shouldNotBeNull()
            host.querySelector(".cat-row").shouldBeNull()
        }

        test("a tree that cannot be loaded explains itself") {
            val host = page(AdminCategoriesUiState.Error(InternalError(debugInfo = "boom")))

            host.querySelector(".cat-tree").shouldBeNull()
            host.querySelector(".empty").shouldNotBeNull()
        }
    })
