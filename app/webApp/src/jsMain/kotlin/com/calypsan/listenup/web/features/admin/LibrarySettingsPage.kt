package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.domain.model.LibraryFolderRef
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLButtonElement

/**
 * Which folders the library watches — the half of library management that outlives onboarding.
 *
 * The setup wizard picks folders once, before the app exists. This is where they change afterwards:
 * a drive gets remounted, a second shelf of books arrives, a path was wrong. Until now web had the
 * first and not the second, so a browser-only admin could configure their server exactly once.
 *
 * Pure in [state]; the session wiring lives one level up.
 *
 * ⛔ **Removing a folder is destructive to the LIBRARY, not to the disk.** `removeFolder` unregisters
 * the path and the books under it stop being served; the files are untouched. That distinction is
 * the whole content of the confirmation, because "Remove" next to a folder full of audiobooks reads
 * like a delete to anyone who has not been told otherwise. iOS asks before removing and Compose
 * does not; web follows iOS, because one click should not be able to empty a library.
 */
@Composable
fun LibrarySettingsPage(
    state: LibrarySettingsUiState,
    scanStarted: Boolean,
    onRemoveFolder: (String) -> Unit,
    onAddPath: (String) -> Unit,
    onScan: () -> Unit,
    onShowBrowser: (Boolean) -> Unit,
    onOpenBrowserPath: (String) -> Unit,
    onBrowserUp: () -> Unit,
    onClearError: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("lset") }) {
        Breadcrumb(trail = listOf("Admin", "Library"), onNavigate = { onOpenAdmin() })
        H1(attrs = { classes("lset-title") }) { Text("Library folders") }

        when (state) {
            is LibrarySettingsUiState.Ready -> {
                ReadyContent(
                    state = state,
                    scanStarted = scanStarted,
                    onRemoveFolder = onRemoveFolder,
                    onAddPath = onAddPath,
                    onScan = onScan,
                    onShowBrowser = onShowBrowser,
                    onOpenBrowserPath = onOpenBrowserPath,
                    onBrowserUp = onBrowserUp,
                    onClearError = onClearError,
                )
            }

            is LibrarySettingsUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("These settings can't be loaded") }
                    P { Text(state.error.message) }
                }
            }

            LibrarySettingsUiState.Loading -> {
                Div(attrs = { classes("skel", "lset-skel") })
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: LibrarySettingsUiState.Ready,
    scanStarted: Boolean,
    onRemoveFolder: (String) -> Unit,
    onAddPath: (String) -> Unit,
    onScan: () -> Unit,
    onShowBrowser: (Boolean) -> Unit,
    onOpenBrowserPath: (String) -> Unit,
    onBrowserUp: () -> Unit,
    onClearError: () -> Unit,
) {
    // A refresh that failed after the page had loaded. Transient and dismissible — the folders
    // above it are still the ones the server has, so the page stays usable underneath.
    state.error?.let { error ->
        Div(attrs = {
            classes("lset-err")
            attr("role", "alert")
        }) {
            Span(attrs = { classes("lset-err-t") }) { Text(error.message) }
            Button(attrs = {
                classes("lset-err-x")
                attr("type", TYPE_BUTTON)
                attr(ATTR_ARIA_LABEL, "Dismiss")
                onClick { onClearError() }
            }) { Icon(WebIcon.X, size = SMALL_ICON) }
        }
    }

    if (scanStarted) {
        Div(attrs = {
            classes("lset-note")
            attr("role", "status")
        }) {
            Text("Folder added. Scanning it now — new books appear as they are found.")
        }
    }

    if (state.showFolderBrowser) {
        FolderBrowser(state, onAddPath, onShowBrowser, onOpenBrowserPath, onBrowserUp)
        return
    }

    // Which folder the trash button was pressed on, if any. View-local by nature: nothing has been
    // asked of the server yet, so there is nothing for the ViewModel to hold.
    var pendingRemove by remember { mutableStateOf<LibraryFolderRef?>(null) }

    Panel(title = state.library.name) {
        if (state.library.folders.isEmpty()) {
            // Reachable: every folder can be removed. A library watching nothing is not broken,
            // but it is why the app looks empty, so it says so rather than showing a bare panel.
            Div(attrs = { classes("lset-empty") }) {
                P { Text("This library watches no folders, so there is nothing to scan.") }
            }
        } else {
            Div(attrs = { classes("lset-list") }) {
                state.library.folders.forEach { folder ->
                    FolderRow(folder, state.isSaving) { pendingRemove = folder }
                }
            }
        }
    }

    Div(attrs = { classes("lset-actions") }) {
        Button(attrs = {
            classes("btn-c")
            attr("type", TYPE_BUTTON)
            disabledWhen(state.isSaving)
            onClick { onShowBrowser(true) }
        }) { Text("Add a folder") }

        Button(attrs = {
            classes("btn-o")
            attr("type", TYPE_BUTTON)
            // Nothing to walk, and a scan already running should not be started twice.
            disabledWhen(state.isScanning || state.library.folders.isEmpty())
            onClick { onScan() }
        }) { Text(if (state.isScanning) "Scanning…" else "Scan now") }
    }

    val pending = pendingRemove
    ConfirmDialog(
        open = pending != null,
        title = "Stop watching this folder?",
        // The path is the only thing that tells two folders apart, so it belongs in the sentence.
        body =
            "${pending?.rootPath ?: "This folder"} will be removed from ${state.library.name} " +
                "and the books in it will stop appearing. Nothing on disk is deleted.",
        confirmLabel = "Stop watching",
        onConfirm = {
            pending?.let { onRemoveFolder(it.id) }
            pendingRemove = null
        },
        onDismiss = { pendingRemove = null },
    )
}

/**
 * One watched folder, and the way to stop watching it.
 *
 * [onAskRemove] opens the confirmation rather than removing — the button is a question, not the
 * action, which is why it is named for asking.
 *
 * ⛔ [LibraryFolderRef.rootPath] is null when the server redacted it for a non-admin caller. This
 * page is admin-only, so that should not happen here — but rendering "null" or an empty row if it
 * ever did would be worse than saying plainly that the path is hidden.
 */
@Composable
private fun FolderRow(
    folder: LibraryFolderRef,
    isSaving: Boolean,
    onAskRemove: () -> Unit,
) {
    Div(attrs = { classes("lset-row") }) {
        Span(attrs = { classes("lset-path", "mono") }) { Text(folder.rootPath ?: "Path hidden") }
        Button(attrs = {
            classes("lset-x")
            attr("type", TYPE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Stop watching ${folder.rootPath ?: "this folder"}")
            disabledWhen(isSaving)
            onClick { onAskRemove() }
        }) { Icon(WebIcon.Trash, size = SMALL_ICON) }
    }
}

/**
 * The server's filesystem, for choosing a folder to add.
 *
 * A mode rather than a modal: the ViewModel carries one `showFolderBrowser` boolean and the browser
 * replaces the list while it is open. A dialog would need its own dismissal contract on top of that
 * flag, and the flag is the only thing the ViewModel actually has.
 *
 * ⛔ **Leaner than the setup wizard's picker, because the data is.** `DirectoryEntryResponse` here
 * carries a name and a path and nothing else — no `hasChildren`, no `itemCount` — so there is no
 * item count to show and no way to know in advance which rows lead anywhere. Every row is therefore
 * navigable, and choosing is a separate button rather than a checkbox: one folder is added at a
 * time here, unlike setup where several are picked at once.
 */
@Composable
private fun FolderBrowser(
    state: LibrarySettingsUiState.Ready,
    onAddPath: (String) -> Unit,
    onShowBrowser: (Boolean) -> Unit,
    onOpenBrowserPath: (String) -> Unit,
    onBrowserUp: () -> Unit,
) {
    Panel(title = "Choose a folder on the server") {
        Div(attrs = { classes("lset-crumb") }) {
            Button(attrs = {
                classes("lset-up")
                attr("type", TYPE_BUTTON)
                attr(ATTR_ARIA_LABEL, "Go up one folder")
                disabledWhen(state.browserIsRoot || state.browserParent == null)
                onClick { onBrowserUp() }
            }) { Icon(WebIcon.ChevronLeft, size = CRUMB_ICON) }
            Span(attrs = { classes("lset-crumb-p", "mono") }) { Text(state.browserPath) }
        }

        when {
            state.isBrowserLoading -> {
                Div(attrs = { classes("skel", "lset-browse-skel") })
            }

            state.browserEntries.isEmpty() -> {
                Div(attrs = { classes("lset-empty") }) { P { Text("Nothing in this folder.") } }
            }

            else -> {
                Div(attrs = { classes("lset-browse") }) {
                    state.browserEntries.forEach { entry -> BrowserRow(entry, onAddPath, onOpenBrowserPath) }
                }
            }
        }

        Div(attrs = { classes("lset-actions") }) {
            Button(attrs = {
                classes("btn-o")
                attr("type", TYPE_BUTTON)
                onClick { onShowBrowser(false) }
            }) { Text("Cancel") }
        }
    }
}

@Composable
private fun BrowserRow(
    entry: DirectoryEntryResponse,
    onAddPath: (String) -> Unit,
    onOpenBrowserPath: (String) -> Unit,
) {
    Div(attrs = { classes("lset-brow") }) {
        Button(attrs = {
            classes("lset-brow-open")
            attr("type", TYPE_BUTTON)
            onClick { onOpenBrowserPath(entry.path) }
        }) {
            Span(attrs = { classes("lset-brow-n") }) { Text(entry.name) }
            Icon(WebIcon.ChevronRight, size = SMALL_ICON)
        }
        Button(attrs = {
            classes("lset-brow-add")
            attr("type", TYPE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Watch ${entry.name}")
            onClick { onAddPath(entry.path) }
        }) { Text("Watch this") }
    }
}

/**
 * `disabled` is a boolean attribute: what makes a control disabled is the attribute being
 * present, not its value — so every site writes the same empty string, and this says it once.
 */
private fun AttrsScope<HTMLButtonElement>.disabledWhen(condition: Boolean) {
    if (condition) attr("disabled", "")
}

private const val ATTR_ARIA_LABEL = "aria-label"

private const val TYPE_BUTTON = "button"

private const val SMALL_ICON = 15

private const val CRUMB_ICON = 18
