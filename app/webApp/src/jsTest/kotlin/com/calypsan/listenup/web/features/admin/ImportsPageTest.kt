package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.core.ImportId
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
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

internal fun importSummary(
    id: String = "im1",
    createdAt: Long = 1_800_000_000_000L,
    status: ImportStatus = ImportStatus.ANALYZED,
    bookCount: Int = 12,
    userCount: Int = 3,
): ImportSummary =
    ImportSummary(
        id = ImportId(id),
        createdAt = createdAt,
        status = status,
        bookCount = bookCount,
        userCount = userCount,
    )

internal fun readyImports(
    imports: List<ImportSummary> = listOf(importSummary()),
    error: com.calypsan.listenup.api.error.AppError? = null,
): ABSImportListUiState.Ready = ABSImportListUiState.Ready(imports = imports, error = error)

private fun page(
    state: ABSImportListUiState,
    onDelete: (ImportSummary) -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onNewImport: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        ImportsPage(
            state = state,
            onDelete = onDelete,
            onClearError = onClearError,
            onRetry = onRetry,
            onNewImport = onNewImport,
            onOpenAdmin = onOpenAdmin,
        )
    }
    return host
}

private fun dialogButton(
    host: HTMLElement,
    label: String,
) = host
    .querySelectorAll("dialog.dlg button")
    .asList()
    .filterIsInstance<HTMLElement>()
    .firstOrNull { it.textContent?.trim() == label }

/**
 * The imports list.
 *
 * What these pin: deleting an import removes the record and NOT the history it wrote — "delete
 * import" reads like "undo import" and it is not, so the dialog says so; and starting one is a link,
 * because the flow needs a file and its own Idle state is where a file can go.
 */
class ImportsPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("an import shows when it ran and what it covered") {
            val host = page(readyImports(listOf(importSummary(bookCount = 12, userCount = 3))))

            host
                .querySelector(".imp-when")
                ?.textContent
                .orEmpty()
                .isNotBlank() shouldBe true
            host.querySelector(".imp-what")?.textContent shouldBe "12 books · 3 listeners"
        }

        test("one of each is singular") {
            val host = page(readyImports(listOf(importSummary(bookCount = 1, userCount = 1))))

            host.querySelector(".imp-what")?.textContent shouldBe "1 book · 1 listener"
        }

        // ⛔ Deleting the record does not undo the import. Leaving that unsaid invites an admin to
        // press Delete expecting the history to come back out.
        test("deleting says the history it wrote stays") {
            val host = page(readyImports())

            (host.querySelector(".imp-del") as HTMLElement).click()
            awaitFrame()

            val text = host.querySelector("dialog.dlg")?.textContent.orEmpty()
            text shouldContain "does not undo the import"
        }

        test("confirming deletes the import that was asked about; cancelling does not") {
            val deleted = mutableListOf<String>()
            val host = page(readyImports(listOf(importSummary(id = "im7"))), onDelete = { deleted += it.id.value })

            (host.querySelector(".imp-del") as HTMLElement).click()
            awaitFrame()
            dialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            deleted shouldContainExactly emptyList()

            (host.querySelector(".imp-del") as HTMLElement).click()
            awaitFrame()
            dialogButton(host, "Delete").shouldNotBeNull().click()
            awaitFrame()

            deleted shouldContainExactly listOf("im7")
        }

        // The flow needs a file before it can do anything, and its own Idle state is what "ready
        // to accept a file" means — so this is a link, not a picker.
        test("New import leaves for the flow rather than picking a file here") {
            var started = 0
            val host = page(readyImports(), onNewImport = { started++ })

            (host.querySelector(".imp-new") as HTMLElement).click()
            awaitFrame()

            started shouldBe 1
            host.querySelector("input[type=file]").shouldBeNull()
        }

        test("a failure is reported, announced, and can be dismissed") {
            var cleared = 0
            val host = page(readyImports(error = InternalError(debugInfo = "boom")), onClearError = { cleared++ })

            host.querySelector(".imp-err-t").shouldNotBeNull().getAttribute("role") shouldBe "alert"
            (host.querySelector(".imp-err-x") as HTMLElement).click()
            awaitFrame()

            cleared shouldBe 1
        }

        test("an empty list says what an import does") {
            val host = page(readyImports(imports = emptyList()))

            host.querySelector(".empty")?.textContent.orEmpty() shouldContain "listening history"
        }

        test("a loading list draws a skeleton") {
            val host = page(ABSImportListUiState.Loading)

            host.querySelector(".imp-skel").shouldNotBeNull()
            host.querySelector(".imp-row").shouldBeNull()
        }

        test("a list that cannot be loaded explains itself and offers a retry that fires") {
            var retries = 0
            val host = page(ABSImportListUiState.Error(InternalError(debugInfo = "boom")), onRetry = { retries++ })

            host.querySelector(".imp-row").shouldBeNull()
            (host.querySelector(".empty button") as HTMLElement).click()
            awaitFrame()

            retries shouldBe 1
        }
    })
