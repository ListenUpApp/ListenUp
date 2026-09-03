package com.calypsan.listenup.web.features.setup

import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

private const val MANY_ITEMS = 248

private val hosts = mutableListOf<HTMLElement>()

internal fun entry(
    name: String = "Audiobooks",
    path: String = "/srv/Audiobooks",
    hasChildren: Boolean = true,
    itemCount: Int = MANY_ITEMS,
) = DirectoryEntry(name = name, path = path, hasChildren = hasChildren, itemCount = itemCount)

internal fun setupState(
    isCheckingStatus: Boolean = false,
    needsSetup: Boolean = true,
    currentPath: String = "/srv",
    parentPath: String? = "/",
    directories: List<DirectoryEntry> = listOf(entry()),
    isLoadingDirectories: Boolean = false,
    isRoot: Boolean = false,
    selectedPaths: Set<String> = emptySet(),
    isCreatingLibrary: Boolean = false,
    error: String? = null,
) = LibrarySetupUiState(
    isCheckingStatus = isCheckingStatus,
    needsSetup = needsSetup,
    currentPath = currentPath,
    parentPath = parentPath,
    directories = directories,
    isLoadingDirectories = isLoadingDirectories,
    isRoot = isRoot,
    selectedPaths = selectedPaths,
    isCreatingLibrary = isCreatingLibrary,
    error = error,
)

private fun page(
    state: LibrarySetupUiState,
    onOpenFolder: (String) -> Unit = {},
    onNavigateUp: () -> Unit = {},
    onToggleFolder: (String) -> Unit = {},
    onComplete: () -> Unit = {},
    onDismissError: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        LibrarySetupPage(state, onOpenFolder, onNavigateUp, onToggleFolder, onComplete, onDismissError)
    }
    return host
}

/**
 * The folder picker a new admin meets before the app.
 *
 * What these pin: opening a folder and choosing one are separate gestures (so drilling down to the
 * right level does not silently add every folder passed through), a leaf directory can still be
 * chosen even though it cannot be entered, the root offers no way up, Continue cannot be pressed
 * with nothing selected, and the page says out loud that these are the server's folders.
 */
class LibrarySetupPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        // A file picker that looks like your own file picker and is not is a trap.
        test("the page says whose filesystem this is") {
            val host = page(setupState())

            (host.querySelector(".lsetup-sub") as HTMLElement).textContent.orEmpty() shouldContain
                "machine running your ListenUp server"
        }

        test("a folder names itself and how much is in it") {
            val host = page(setupState(directories = listOf(entry(name = "Audiobooks", itemCount = MANY_ITEMS))))

            (host.querySelector(".lsetup-row-n") as HTMLElement).textContent shouldBe "Audiobooks"
            (host.querySelector(".lsetup-row-c") as HTMLElement).textContent shouldBe "248 items"
        }

        test("a folder holding one thing reads as '1 item'") {
            val host = page(setupState(directories = listOf(entry(itemCount = 1))))

            (host.querySelector(".lsetup-row-c") as HTMLElement).textContent shouldBe "1 item"
        }

        // Drilling down must not select. A single tap doing both silently adds four folders on
        // the way to the fifth.
        test("opening a folder does not choose it") {
            val opened = mutableListOf<String>()
            val toggled = mutableListOf<String>()
            val host =
                page(
                    setupState(directories = listOf(entry(path = "/srv/Audiobooks"))),
                    onOpenFolder = { opened += it },
                    onToggleFolder = { toggled += it },
                )

            (host.querySelector(".lsetup-open") as HTMLElement).click()

            opened shouldBe listOf("/srv/Audiobooks")
            toggled shouldBe emptyList()
        }

        test("choosing a folder does not open it") {
            val opened = mutableListOf<String>()
            val toggled = mutableListOf<String>()
            val host =
                page(
                    setupState(directories = listOf(entry(path = "/srv/Audiobooks"))),
                    onOpenFolder = { opened += it },
                    onToggleFolder = { toggled += it },
                )

            (host.querySelector(".lsetup-check") as HTMLElement).click()

            toggled shouldBe listOf("/srv/Audiobooks")
            opened shouldBe emptyList()
        }

        test("a chosen folder says so to a screen reader, not only to an eye") {
            val chosen = page(setupState(selectedPaths = setOf("/srv/Audiobooks")))
            val not = page(setupState(selectedPaths = emptySet()))

            (chosen.querySelector(".lsetup-check") as HTMLElement).getAttribute("aria-checked") shouldBe "true"
            (chosen.querySelector(".lsetup-row") as HTMLElement).classList.contains("on") shouldBe true
            (not.querySelector(".lsetup-check") as HTMLElement).getAttribute("aria-checked") shouldBe "false"
        }

        // A leaf directory full of audiobooks is the common case.
        test("a folder with nothing below it cannot be entered but can still be chosen") {
            val host = page(setupState(directories = listOf(entry(hasChildren = false))))

            (host.querySelector(".lsetup-open") as HTMLButtonElement).disabled shouldBe true
            (host.querySelector(".lsetup-check") as HTMLButtonElement).disabled shouldBe false
        }

        test("the current path is shown, and up is offered") {
            var ups = 0
            val host = page(setupState(currentPath = "/srv/media", isRoot = false), onNavigateUp = { ups++ })

            (host.querySelector(".lsetup-path") as HTMLElement).textContent shouldBe "/srv/media"
            (host.querySelector(".lsetup-up") as HTMLElement).click()

            ups shouldBe 1
        }

        test("the root offers no way up") {
            val host = page(setupState(currentPath = "/", parentPath = null, isRoot = true))

            (host.querySelector(".lsetup-up") as HTMLButtonElement).disabled shouldBe true
        }

        // The ViewModel would refuse with an error; a button that exists only to tell you off is
        // worse than one that plainly cannot be pressed yet.
        test("Continue cannot be pressed with nothing chosen") {
            val host = page(setupState(selectedPaths = emptySet()))

            (host.querySelector(".lsetup-go") as HTMLButtonElement).disabled shouldBe true
        }

        test("Continue commits the choice once something is chosen") {
            var completed = 0
            val host = page(setupState(selectedPaths = setOf("/srv/Audiobooks")), onComplete = { completed++ })

            val go = host.querySelector(".lsetup-go") as HTMLButtonElement
            go.disabled shouldBe false
            go.click()

            completed shouldBe 1
        }

        test("the count says how many folders are chosen") {
            page(setupState(selectedPaths = emptySet())).textContent.orEmpty() shouldContain "No folders chosen"
            page(setupState(selectedPaths = setOf("/a"))).textContent.orEmpty() shouldContain "1 folder chosen"
            page(setupState(selectedPaths = setOf("/a", "/b"))).textContent.orEmpty() shouldContain "2 folders chosen"
        }

        // Pressing it twice would register every folder twice.
        test("Continue is unavailable while setup is running, and says it is running") {
            val host = page(setupState(selectedPaths = setOf("/a"), isCreatingLibrary = true))

            val go = host.querySelector(".lsetup-go") as HTMLButtonElement
            go.disabled shouldBe true
            go.textContent.orEmpty() shouldContain "Setting up"
        }

        test("an error is shown and can be dismissed") {
            var dismissed = 0
            val host = page(setupState(error = "That folder could not be read."), onDismissError = { dismissed++ })

            host.textContent.orEmpty() shouldContain "That folder could not be read."
            (host.querySelector(".lsetup-err-x") as HTMLElement).click()

            dismissed shouldBe 1
        }

        test("an empty folder says so rather than showing nothing") {
            val host = page(setupState(directories = emptyList()))

            host.textContent.orEmpty() shouldContain "Nothing in this folder"
        }

        test("a folder still loading draws a skeleton rather than an empty state") {
            val host = page(setupState(isLoadingDirectories = true, directories = emptyList()))

            host.querySelector(".lsetup-skel").shouldNotBeNull()
            host.textContent.orEmpty().contains("Nothing in this folder") shouldBe false
        }
    })
