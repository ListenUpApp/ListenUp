package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.uploads.UploadedBook
import com.calypsan.listenup.api.dto.uploads.UploadedBookStatus
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.files.File
import com.calypsan.listenup.web.UPLOAD_BYTE_CEILING
import com.calypsan.listenup.web.candidatesFrom
import com.calypsan.listenup.web.relPathOf

internal fun uploaded(
    title: String,
    status: UploadedBookStatus,
    detail: String? = null,
) = UploadedBook(title = title, status = status, rootRelPath = null, detail = detail)

internal fun finished(
    imported: List<UploadedBook> = emptyList(),
    duplicates: List<UploadedBook> = emptyList(),
    failed: List<UploadedBook> = emptyList(),
) = UploadBooksUiState.Finished(imported = imported, duplicates = duplicates, failed = failed)

private fun buttons(host: HTMLElement): List<HTMLButtonElement> =
    host.querySelectorAll("button").asList().filterIsInstance<HTMLButtonElement>()

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? = buttons(host).firstOrNull { it.textContent?.trim() == label }

private fun text(
    host: HTMLElement,
    selector: String,
): String? = (host.querySelector(selector) as? HTMLElement)?.textContent?.trim()

/**
 * Putting books into the library from the browser.
 *
 * ⛔ Two things these hold. **A duplicate is not a failure** — a run where everything was already
 * present is a complete, successful outcome and must not read as one. And **unknown progress is
 * indeterminate, never zero**: a selection that could not report its sizes is a bar that moves
 * without a number, because a bar pinned at 0% reads as a hung upload.
 */
class UploadTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun page(
            state: UploadBooksUiState,
            onFilesPicked: (List<File>) -> Unit = {},
            onCancel: () -> Unit = {},
            onReset: () -> Unit = {},
            onOpenAdmin: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                UploadPage(
                    state = state,
                    onFilesPicked = onFilesPicked,
                    onCancel = onCancel,
                    onReset = onReset,
                    onOpenAdmin = onOpenAdmin,
                )
            }

        test("both ways of picking are offered") {
            // A folder-only picker would make a single loose .m4b unimportable; a files-only one
            // would throw away the structure the server groups on.
            val host = page(UploadBooksUiState.Idle)

            button(host, "Choose a folder").shouldNotBeNull()
            button(host, "Choose files").shouldNotBeNull()
        }

        test("the folder picker asks the browser for the paths inside it") {
            // ⛔ `webkitdirectory` is the only thing that makes each file report where it sits in
            // the pick, and that structure is exactly what the server groups books on.
            val host = page(UploadBooksUiState.Idle)

            val folder = host.querySelector("#upl-folder") as HTMLInputElement
            folder.hasAttribute("webkitdirectory") shouldBe true
            folder.hasAttribute("multiple") shouldBe true

            val files = host.querySelector("#upl-files") as HTMLInputElement
            files.hasAttribute("webkitdirectory") shouldBe false
            files.hasAttribute("multiple") shouldBe true
        }

        test("an upload in flight names the file and offers a way out") {
            var cancelled = 0
            val host =
                page(
                    UploadBooksUiState.Uploading(fileIndex = 2, fileCount = 9, filename = "03.m4b", fraction = 0.5f),
                    onCancel = { cancelled++ },
                )

            text(host, ".upl-step").shouldNotBeNull() shouldContain "Sending 03.m4b (3 of 9)"
            (host.querySelector(".upl-bar-fill") as HTMLElement).getAttribute("style") shouldContain "50%"

            button(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            cancelled shouldBe 1
        }

        test("a selection that could not report its size gets an indeterminate bar, not a stuck one") {
            val host =
                page(UploadBooksUiState.Uploading(fileIndex = 0, fileCount = 3, filename = "a.m4b", fraction = null))

            (host.querySelector(".upl-bar") as HTMLElement).classList.contains("is-idle") shouldBe true
            (host.querySelector(".upl-bar-fill") as HTMLElement).getAttribute("style").shouldBeNull()
        }

        test("finalizing says what the server is doing, with no false progress") {
            val host = page(UploadBooksUiState.Finalizing)

            text(host, ".upl-step").shouldNotBeNull() shouldContain "Adding them to your library"
            (host.querySelector(".upl-bar") as HTMLElement).classList.contains("is-idle") shouldBe true
        }

        test("progress is announced, because a long upload is when someone looks away") {
            val host = page(UploadBooksUiState.Finalizing)

            (host.querySelector(".upl-live") as HTMLElement).getAttribute("aria-live") shouldBe "polite"
        }

        test("a finished run groups the three outcomes separately") {
            val host =
                page(
                    finished(
                        imported = listOf(uploaded("Dune", UploadedBookStatus.IMPORTED)),
                        duplicates = listOf(uploaded("Mistborn", UploadedBookStatus.DUPLICATE)),
                        failed = listOf(uploaded("Broken", UploadedBookStatus.FAILED, detail = "no audio stream")),
                    ),
                )

            host.querySelectorAll(".upl-n").asList().map { (it as HTMLElement).textContent?.trim() } shouldContainExactly
                listOf("Dune", "Mistborn", "Broken")
            text(host, ".upl-d") shouldBe "no audio stream"
            host.textContent.orEmpty() shouldContain "Already in your library (1)"
        }

        test("a group with nothing in it is not drawn at all") {
            val host = page(finished(imported = listOf(uploaded("Dune", UploadedBookStatus.IMPORTED))))

            host.textContent.orEmpty() shouldContain "Added (1)"
            host.textContent.orEmpty() shouldNotContain "Couldn't be added"
            host.textContent.orEmpty() shouldNotContain "Already in your library"
        }

        test("everything already present is a success, not a run that added nothing") {
            // ⛔ "0 books added" is the wrong sentence for a complete, correct outcome.
            finishedSummary(finished(duplicates = listOf(uploaded("A", UploadedBookStatus.DUPLICATE)))) shouldBe
                "Everything you picked was already in your library."
        }

        test("one book is a book, and the other two outcomes are counted beside it") {
            finishedSummary(finished(imported = listOf(uploaded("A", UploadedBookStatus.IMPORTED)))) shouldBe
                "1 book added."
            finishedSummary(
                finished(
                    imported = List(3) { uploaded("A$it", UploadedBookStatus.IMPORTED) },
                    duplicates = listOf(uploaded("B", UploadedBookStatus.DUPLICATE)),
                    failed = listOf(uploaded("C", UploadedBookStatus.FAILED)),
                ),
            ) shouldBe "3 books added, 1 already here, 1 failed."
        }

        test("a run where nothing could be added says exactly that") {
            finishedSummary(finished(failed = listOf(uploaded("A", UploadedBookStatus.FAILED)))) shouldBe
                "Nothing could be added."
        }

        test("a finished run offers another go") {
            var reset = 0
            val host = page(finished(imported = listOf(uploaded("Dune", UploadedBookStatus.IMPORTED))), onReset = { reset++ })

            button(host, "Add more").shouldNotBeNull().click()
            awaitFrame()
            reset shouldBe 1
        }

        test("a folder pick keeps each file's path inside it") {
            // ⛔ That structure IS the upload's payload: the server groups books on it, and a
            // flattened pick would hand it a pile of filenames to guess at instead.
            relPathOf(looseFile("dune.m4b")) shouldBe "dune.m4b"

            relPathOf(inFolderFile("01.m4b", "The Way of Kings/01.m4b")) shouldBe "The Way of Kings/01.m4b"
        }

        test("a pick becomes candidates carrying their own bytes and paths") {
            val candidates = candidatesFrom(listOf(inFolderFile("01.m4b", "Dune/01.m4b", "hello"))).shouldNotBeNull()

            candidates.map { it.relPath } shouldContainExactly listOf("Dune/01.m4b")
            candidates.single().source.filename shouldBe "01.m4b"
            candidates.single().source.size shouldBe 5L
        }

        test("a selection too large for the tab is refused before anything is read") {
            // ⛔ Refused up front rather than part-way through: every file is read into memory
            // because `FileSource.openChannel()` is synchronous and a browser's read is not, so
            // the alternative to a sentence here is an allocation crash mid-folder.
            candidatesFrom(listOf(oversizeFile())).shouldBeNull()
        }

        test("a failed session says why and offers to start over") {
            var reset = 0
            val host = page(UploadBooksUiState.Error(InternalError(debugInfo = "boom")), onReset = { reset++ })

            text(host, ".upl-err").shouldNotBeNull()
            button(host, "Try again").shouldNotBeNull().click()
            awaitFrame()
            reset shouldBe 1
        }
    })

/**
 * A picked file, optionally one the browser reported a folder path for.
 *
 * ⛔ `webkitRelativePath` and `size` are read-only getters on a real `File`, so they are redefined
 * on the instance rather than assigned — the alternative is a hand-rolled fake, which would test
 * the fake rather than the property `relPathOf` actually reads.
 */
private fun browserFile(
    name: String,
    content: String,
    relativePath: String?,
    size: Double?,
): File {
    val file = File(arrayOf(content).unsafeCast<Array<Any>>(), name)
    relativePath?.let { defineProperty(file, "webkitRelativePath", it) }
    size?.let { defineProperty(file, "size", it) }
    return file
}

private fun defineProperty(
    target: Any,
    name: String,
    value: Any,
) {
    val descriptor = js("{}")
    descriptor.value = value
    descriptor.configurable = true
    js("Object").defineProperty(target, name, descriptor)
}

private fun looseFile(name: String) = browserFile(name, "x", relativePath = null, size = null)

private fun inFolderFile(
    name: String,
    relativePath: String,
    content: String = "x",
) = browserFile(name, content, relativePath, size = null)

private fun oversizeFile() = browserFile("big.m4b", "x", relativePath = null, size = UPLOAD_BYTE_CEILING + 1)
