package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.mutableStateOf
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * Delete Book on the web — the admin's way to remove a book's folder from the server's disk.
 *
 * ⛔ The dialog is the whole safety of this feature. It has to say, in the natives' words, that
 * the FOLDER goes (not just "remove from library"), how many files ListenUp knows are in it, and
 * that every device loses the book. And it has to stay open when the server refuses, because the
 * refusal — "another book shares this folder" — is only useful where the decision is being made.
 */
class BookDetailDeleteTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun rendered(
            state: () -> BookDetailUiState,
            onDeleteBook: () -> Unit = {},
            onClearDeleteError: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = state(),
                    tab = "overview",
                    onSelectTab = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onRetryConnection = {},
                    documents = sampleDocuments(),
                    onDeleteBook = onDeleteBook,
                    onClearDeleteError = onClearDeleteError,
                )
            }

        suspend fun openDeleteDialog(host: HTMLElement) {
            (host.querySelector(".bd-actions .btn-icon[aria-haspopup='menu']") as HTMLElement).click()
            awaitFrame()
            host
                .querySelectorAll(".menu-i")
                .asList()
                .filterIsInstance<HTMLButtonElement>()
                .first { it.textContent?.trim() == "Delete book…" }
                .click()
            awaitFrame()
        }

        fun dialog(host: HTMLElement): HTMLElement? = host.querySelector("dialog.dlg") as? HTMLElement

        fun actions(host: HTMLElement): List<HTMLButtonElement> =
            host.querySelectorAll("dialog.dlg .dlg-actions button").asList().filterIsInstance<HTMLButtonElement>()

        test("the dialog names the book, the folder, what ListenUp tracks in it, and every device") {
            val host = rendered({ readyBook().copy(isAdmin = true) })

            openDeleteDialog(host)

            val open = dialog(host).shouldNotBeNull()
            open.querySelector(".dlg-t")?.textContent shouldBe "Delete “The Institute”?"
            // Three audio parts (178 + 182 + 152 MB) plus two documents (2 MB + 512 KB): five
            // tracked files. Documents count because they sit in the same folder and go with it.
            open.querySelector(".dlg-p")?.textContent shouldBe
                "This permanently deletes the book’s folder from your server. ListenUp tracks 5 files in it " +
                "(514 MB), and everything else in that folder goes too — PDFs, artwork, bonus material — " +
                "whether ListenUp shows it or not. The book disappears from every device, and this can’t be undone."
            actions(host).map { it.textContent } shouldBe listOf("Cancel", "Delete forever")
        }

        test("confirming asks the ViewModel to delete") {
            var deletes = 0
            val host = rendered({ readyBook().copy(isAdmin = true) }, onDeleteBook = { deletes++ })

            openDeleteDialog(host)
            actions(host)[1].click()
            awaitFrame()

            deletes shouldBe 1
        }

        test("a delete in flight holds the confirm button, so a second press cannot race the first") {
            val host = rendered({ readyBook().copy(isAdmin = true, isDeletingBook = true) })

            openDeleteDialog(host)

            actions(host)[1].disabled shouldBe true
        }

        test("a refusal stays in the open dialog, naming the book that blocked it") {
            val state = mutableStateOf<BookDetailUiState>(readyBook().copy(isAdmin = true))
            val host = rendered({ state.value })

            openDeleteDialog(host)
            state.value =
                readyBook().copy(
                    isAdmin = true,
                    deleteError = BookError.FolderNotExclusive(otherBookId = "b-2", otherBookTitle = "Joyland"),
                )
            awaitFrame()

            dialog(host).shouldNotBeNull()
            host.querySelector("dialog.dlg .dlg-err")?.textContent shouldBe
                "“Joyland” is in the same folder, so deleting this book would take it too. Nothing was deleted."
        }

        test("any other refusal renders its own message") {
            val host =
                rendered({
                    readyBook().copy(isAdmin = true, deleteError = TransportError.NetworkUnavailable())
                })

            openDeleteDialog(host)

            host.querySelector("dialog.dlg .dlg-err")?.textContent shouldBe TransportError.NetworkUnavailable().message
        }

        test("dismissing closes the dialog and clears the refusal, so the next attempt starts clean") {
            var cleared = 0
            val host =
                rendered(
                    { readyBook().copy(isAdmin = true, deleteError = TransportError.NetworkUnavailable()) },
                    onClearDeleteError = { cleared++ },
                )

            openDeleteDialog(host)
            actions(host)[0].click()
            awaitFrame()

            dialog(host).shouldBeNull()
            (cleared >= 1) shouldBe true
        }
    })
