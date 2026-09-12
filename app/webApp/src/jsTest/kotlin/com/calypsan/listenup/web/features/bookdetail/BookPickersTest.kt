package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import com.calypsan.listenup.api.dto.SharePermission

private fun shelf(
    id: String = "s1",
    name: String = "Bedtime",
) = Shelf(
    id = ShelfId(id),
    name = name,
    description = null,
    isPrivate = false,
    ownerId = "u1",
    ownerDisplayName = "Simon",
    bookCount = 0,
    totalDurationSeconds = 0L,
    createdAtMs = 0L,
    updatedAtMs = 0L,
)

private fun collectionOf(
    id: String = "c1",
    name: String = "Staff picks",
) = Collection(
    id = id,
    name = name,
    ownerId = "u1",
    isInbox = false,
    isSystem = false,
    bookCount = 0,
    callerPermission = SharePermission.Write,
    isOwner = true,
)

private fun targets(host: HTMLElement): List<String> =
    host
        .querySelectorAll(".sel-target")
        .asList()
        .filterIsInstance<HTMLElement>()
        .map { it.textContent.orEmpty().trim() }

/**
 * Filing a book from its own page.
 *
 * ⛔ Reachable before this only by leaving the book, selecting it in the library grid and using
 * multi-select — the capability existed, the entry point did not. Both natives offer it from the
 * book's own overflow menu.
 */
class BookPickersTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun dialogs(
            ready: BookDetailUiState.Ready,
            pickers: BookPickers = BookPickers(),
        ): HTMLElement = mounts.mount { BookPickerDialogs(ready = ready, pickers = pickers) }

        fun closeAny(host: HTMLElement) {
            (host.querySelector("dialog") as? HTMLDialogElement)?.close()
        }

        test("the shelf picker lists the listener's own shelves") {
            val host =
                dialogs(
                    readyBook().copy(showShelfPicker = true),
                    BookPickers(myShelves = listOf(shelf(name = "Bedtime"), shelf("s2", "Commute"))),
                )

            targets(host) shouldContainExactly listOf("Bedtime", "Commute")
            closeAny(host)
        }

        test("picking a shelf reports which one") {
            val picked = mutableListOf<String>()
            val host =
                dialogs(
                    readyBook().copy(showShelfPicker = true),
                    BookPickers(myShelves = listOf(shelf("s-bedtime")), onAddToShelf = { picked += it }),
                )

            (host.querySelector(".sel-target") as HTMLElement).click()
            awaitFrame()

            picked shouldContainExactly listOf("s-bedtime")
            closeAny(host)
        }

        test("a listener with no shelves is told so, and can still make one") {
            val host = dialogs(readyBook().copy(showShelfPicker = true), BookPickers())

            host.textContent.orEmpty() shouldContain "no shelves yet"
            host.textContent.orEmpty() shouldContain "Create shelf"
            closeAny(host)
        }

        // ⛔ Collections are admin-managed. The gate is on `isAdmin` as well as on the flag —
        // Android does the same and calls it defence in depth: the picker must not render for a
        // non-admin even if the flag behind it were ever set true.
        test("a non-admin never sees the collection picker, flag or no flag") {
            val host =
                dialogs(
                    readyBook().copy(showCollectionPicker = true, isAdmin = false),
                    BookPickers(collections = listOf(collectionOf())),
                )

            host.querySelector("dialog").shouldBeNull()
        }

        test("an admin does") {
            val host =
                dialogs(
                    readyBook().copy(showCollectionPicker = true, isAdmin = true),
                    BookPickers(collections = listOf(collectionOf(name = "Staff picks"))),
                )

            targets(host) shouldContainExactly listOf("Staff picks")
            closeAny(host)
        }

        test("neither picker is open until it is asked for") {
            dialogs(readyBook()).querySelector("dialog").shouldBeNull()
        }

        test("a refused filing is reported outside the picker, because the picker closes") {
            // ⛔ The dialog dismisses on a successful pick, so a failure rendered only inside it
            // would disappear along with it — leaving the reader believing the book was filed.
            var cleared = 0
            val host =
                dialogs(
                    readyBook().copy(shelfError = "That shelf is gone."),
                    BookPickers(onClearShelfError = { cleared++ }),
                )

            val notice = (host.querySelector(".bd-pick-err") as? HTMLElement).shouldNotBeNull()
            notice.textContent?.trim() shouldBe "That shelf is gone."
            notice.getAttribute("aria-live") shouldBe "polite"

            notice.click()
            awaitFrame()
            cleared shouldBe 1
        }

        test("a collection failure is reported the same way") {
            val host = dialogs(readyBook().copy(collectionError = "Not allowed."))

            (host.querySelector(".bd-pick-err") as HTMLElement).textContent?.trim() shouldBe "Not allowed."
        }

        test("a filing in flight holds the picker's choices still") {
            val host =
                dialogs(
                    readyBook().copy(showShelfPicker = true, isAddingToShelf = true),
                    BookPickers(myShelves = listOf(shelf())),
                )

            (host.querySelector(".sel-target") as HTMLButtonElement).hasAttribute("disabled") shouldBe true
            closeAny(host)
        }

        test("the menu offers a collection entry only to an admin") {
            filingActions(readyBook().copy(isAdmin = false), {}, {}).map { it.label } shouldContainExactly
                listOf("Add to shelf")
            filingActions(readyBook().copy(isAdmin = true), {}, {}).map { it.label } shouldContainExactly
                listOf("Add to shelf", "Add to collection")
        }
    })
