package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.LibraryFolderRef
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

private val hosts = mutableListOf<HTMLElement>()

internal fun library(folders: List<LibraryFolderRef> = listOf(LibraryFolderRef("f1", "/srv/Audiobooks"))) =
    Library(
        id = "lib-1",
        name = "Library",
        folders = folders,
        metadataPrecedence = "embedded",
        accessMode = AccessMode.OPEN,
        createdByUserId = null,
        createdAt = 0L,
        revision = 1L,
    )

internal fun readyLibrary(
    folders: List<LibraryFolderRef> = listOf(LibraryFolderRef("f1", "/srv/Audiobooks")),
    isSaving: Boolean = false,
    isScanning: Boolean = false,
    error: com.calypsan.listenup.api.error.AppError? = null,
    showFolderBrowser: Boolean = false,
    isBrowserLoading: Boolean = false,
    browserPath: String = "/srv",
    browserParent: String? = "/",
    browserEntries: List<DirectoryEntryResponse> = listOf(DirectoryEntryResponse("media", "/srv/media")),
    browserIsRoot: Boolean = false,
) = LibrarySettingsUiState.Ready(
    library = library(folders),
    isSaving = isSaving,
    isScanning = isScanning,
    error = error,
    showFolderBrowser = showFolderBrowser,
    isBrowserLoading = isBrowserLoading,
    browserPath = browserPath,
    browserParent = browserParent,
    browserEntries = browserEntries,
    browserIsRoot = browserIsRoot,
)

// Cancel is first in the DOM so a hurried Return lands on the safe choice; confirm follows it.
private fun dialogButton(
    host: HTMLElement,
    index: Int,
) = host.querySelectorAll("dialog.dlg .dlg-actions button").item(index) as HTMLElement

private fun cancelButton(host: HTMLElement) = dialogButton(host, 0)

private fun confirmButton(host: HTMLElement) = dialogButton(host, 1)

@Suppress("LongParameterList")
private fun page(
    state: LibrarySettingsUiState,
    scanStarted: Boolean = false,
    onRemoveFolder: (String) -> Unit = {},
    onAddPath: (String) -> Unit = {},
    onScan: () -> Unit = {},
    onShowBrowser: (Boolean) -> Unit = {},
    onOpenBrowserPath: (String) -> Unit = {},
    onBrowserUp: () -> Unit = {},
    onClearError: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        LibrarySettingsPage(
            state,
            scanStarted,
            onRemoveFolder,
            onAddPath,
            onScan,
            onShowBrowser,
            onOpenBrowserPath,
            onBrowserUp,
            onClearError,
            onOpenAdmin,
        )
    }
    return host
}

/**
 * Which folders the library watches, after onboarding is over.
 *
 * What these pin: a folder's removal is offered as "stop watching" rather than as a delete (the
 * files are untouched, and the control says so), scanning is unavailable when there is nothing to
 * walk or a scan is already running, the browser is a mode that replaces the list rather than an
 * overlay on top of it, and a library watching nothing explains itself rather than showing a bare
 * panel.
 */
class LibrarySettingsPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("each watched folder shows its path on the server") {
            val host = page(readyLibrary(folders = listOf(LibraryFolderRef("f1", "/srv/Audiobooks"))))

            (host.querySelector(".lset-path") as HTMLElement).textContent shouldBe "/srv/Audiobooks"
        }

        // Admin-only page, so this should not happen — but "null" in a path row would be worse.
        test("a redacted path says so rather than rendering nothing") {
            val host = page(readyLibrary(folders = listOf(LibraryFolderRef("f1", null))))

            (host.querySelector(".lset-path") as HTMLElement).textContent shouldBe "Path hidden"
        }

        test("removing a folder reports the folder id, not its path") {
            val removed = mutableListOf<String>()
            val host =
                page(
                    readyLibrary(folders = listOf(LibraryFolderRef("f9", "/srv/Audiobooks"))),
                    onRemoveFolder = { removed += it },
                )

            (host.querySelector(".lset-x") as HTMLElement).click()
            awaitFrame()
            confirmButton(host).click()

            removed shouldBe listOf("f9")
        }

        // One click should not be able to empty a library. iOS asks; so does this.
        test("the trash button asks before it removes") {
            val removed = mutableListOf<String>()
            val host = page(readyLibrary(), onRemoveFolder = { removed += it })

            (host.querySelector(".lset-x") as HTMLElement).click()
            awaitFrame()

            host.querySelector("dialog.dlg").shouldNotBeNull()
            removed shouldBe emptyList()
        }

        test("cancelling the confirmation leaves the folder watched") {
            val removed = mutableListOf<String>()
            val host = page(readyLibrary(), onRemoveFolder = { removed += it })

            (host.querySelector(".lset-x") as HTMLElement).click()
            awaitFrame()
            cancelButton(host).click()
            awaitFrame()

            removed shouldBe emptyList()
            host.querySelector("dialog.dlg") shouldBe null
        }

        // "Remove" beside a folder full of audiobooks reads like a delete to anyone not told
        // otherwise. The files are never touched, and the confirmation is where that is said.
        test("the confirmation names the folder and says the files on disk are safe") {
            val host = page(readyLibrary(folders = listOf(LibraryFolderRef("f9", "/srv/Audiobooks"))))

            (host.querySelector(".lset-x") as HTMLElement).click()
            awaitFrame()

            val body = (host.querySelector("dialog.dlg .dlg-p") as HTMLElement).textContent.orEmpty()
            body shouldContain "/srv/Audiobooks"
            body shouldContain "Nothing on disk is deleted."
        }

        // Reachable: every folder can be removed.
        test("a library watching nothing explains why the app looks empty") {
            val host = page(readyLibrary(folders = emptyList()))

            host.textContent.orEmpty() shouldContain "watches no folders"
        }

        test("a scan cannot be started with nothing to walk") {
            val host = page(readyLibrary(folders = emptyList()))

            scanButton(host).disabled shouldBe true
        }

        test("a scan cannot be started twice, and says it is running") {
            val host = page(readyLibrary(isScanning = true))

            scanButton(host).disabled shouldBe true
            scanButton(host).textContent.orEmpty() shouldContain "Scanning"
        }

        test("a scan starts when there is something to walk") {
            var scans = 0
            val host = page(readyLibrary(), onScan = { scans++ })

            scanButton(host).click()

            scans shouldBe 1
        }

        test("Add a folder opens the browser") {
            val shown = mutableListOf<Boolean>()
            val host = page(readyLibrary(), onShowBrowser = { shown += it })

            (host.querySelector(".lset-actions .btn-c") as HTMLElement).click()

            shown shouldBe listOf(true)
        }

        test("Cancel closes the browser again") {
            val shown = mutableListOf<Boolean>()
            val host = page(readyLibrary(showFolderBrowser = true), onShowBrowser = { shown += it })

            (host.querySelector(".lset-actions .btn-o") as HTMLElement).click()

            shown shouldBe listOf(false)
        }

        test("the browser replaces the folder list rather than sitting over it") {
            val host = page(readyLibrary(showFolderBrowser = true))

            host.querySelector(".lset-browse").shouldNotBeNull()
            host.querySelector(".lset-list") shouldBe null
        }

        test("adding a folder from the browser reports the path it chose") {
            val added = mutableListOf<String>()
            val host =
                page(
                    readyLibrary(
                        showFolderBrowser = true,
                        browserEntries = listOf(DirectoryEntryResponse("media", "/srv/media")),
                    ),
                    onAddPath = { added += it },
                )

            (host.querySelector(".lset-brow-add") as HTMLElement).click()

            added shouldBe listOf("/srv/media")
        }

        // Browsing into a folder and watching it are different intentions, as in the setup wizard.
        test("opening a folder in the browser does not add it") {
            val added = mutableListOf<String>()
            val opened = mutableListOf<String>()
            val host =
                page(
                    readyLibrary(showFolderBrowser = true),
                    onAddPath = { added += it },
                    onOpenBrowserPath = { opened += it },
                )

            (host.querySelector(".lset-brow-open") as HTMLElement).click()

            opened shouldBe listOf("/srv/media")
            added shouldBe emptyList()
        }

        test("the browser's root offers no way up") {
            val host = page(readyLibrary(showFolderBrowser = true, browserIsRoot = true, browserParent = null))

            (host.querySelector(".lset-up") as HTMLButtonElement).disabled shouldBe true
        }

        // browserIsRoot and browserParent arrive from the server independently, and it is the
        // former that says "you may not go higher" — a browse root can have a parent directory
        // that is simply off-limits. Android and iOS both gate on browserIsRoot alone, and
        // browserNavigateUp checks only the parent, so this button is the guard.
        test("a root with a parent above it still offers no way up") {
            val host = page(readyLibrary(showFolderBrowser = true, browserIsRoot = true, browserParent = "/"))

            (host.querySelector(".lset-up") as HTMLButtonElement).disabled shouldBe true
        }

        test("a folder below the root offers a way up") {
            val host = page(readyLibrary(showFolderBrowser = true, browserIsRoot = false, browserParent = "/srv"))

            (host.querySelector(".lset-up") as HTMLButtonElement).disabled shouldBe false
        }

        test("a folder still loading draws a skeleton rather than an empty folder") {
            val host =
                page(readyLibrary(showFolderBrowser = true, isBrowserLoading = true, browserEntries = emptyList()))

            host.querySelector(".lset-browse-skel").shouldNotBeNull()
            host.textContent.orEmpty().contains("Nothing in this folder") shouldBe false
        }

        // A one-shot the ViewModel emits once; the page is told, not asked.
        test("a started scan is announced, and only when it started") {
            page(readyLibrary(), scanStarted = false).querySelector(".lset-note") shouldBe null
            page(readyLibrary(), scanStarted = true).textContent.orEmpty() shouldContain "Scanning it now"
        }

        // The folders above it are still the ones the server has, so the page stays usable.
        test("a refresh failure is transient and dismissible") {
            var cleared = 0
            val host = page(readyLibrary(error = InternalError()), onClearError = { cleared++ })

            host.querySelector(".lset-err").shouldNotBeNull()
            host.querySelector(".lset-list").shouldNotBeNull()
            (host.querySelector(".lset-err-x") as HTMLElement).click()

            cleared shouldBe 1
        }

        test("a failed initial load is terminal and shows no folder list") {
            val host = page(LibrarySettingsUiState.Error(InternalError()))

            host.textContent.orEmpty() shouldContain "can't be loaded"
            host.querySelector(".lset-list") shouldBe null
        }

        test("loading draws a skeleton") {
            val host = page(LibrarySettingsUiState.Loading)

            host.querySelector(".lset-skel").shouldNotBeNull()
        }

        test("the breadcrumb leads back to Admin") {
            var opened = 0
            val host = page(readyLibrary(), onOpenAdmin = { opened++ })

            (host.querySelector(".crumb a") as HTMLElement).click()

            opened shouldBe 1
        }
    })

/** The Scan control, wherever it sits in the action row. */
private fun scanButton(host: HTMLElement): HTMLButtonElement {
    val buttons = host.querySelectorAll(".lset-actions button")
    return (0 until buttons.length)
        .map { buttons.item(it) as HTMLButtonElement }
        .first { it.textContent.orEmpty().contains("Scan") }
}
