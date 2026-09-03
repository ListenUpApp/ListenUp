package com.calypsan.listenup.web.features.setup

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Point ListenUp at your audiobooks — the first thing a new admin does, and until now the one
 * thing they could not do from a browser.
 *
 * A server with no folders has an empty library and no way to fill it. Android and iOS have had
 * this wizard since the beginning; a web-only admin reached the shell, saw nothing, and had no
 * control anywhere in the app that would have helped.
 *
 * Pure in [state]; the session wiring lives one level up.
 *
 * ⛔ **This is a browser of the SERVER's filesystem, not yours.** Every path here comes from
 * `LibraryAdminService.browseFilesystem` and is absolute on the machine running ListenUp — which
 * for a self-hosted server is very often not the machine you are sitting at. The header says so,
 * because a file picker that looks like your own file picker and is not is a trap.
 *
 * The design bundle this follows (`variantLibrarySetup`, 2026-06-07) also drew a "Library created"
 * confirmation and an "Add another library" loop. Both are gone from the ViewModel: it settled on
 * a single library whose folders this wizard picks, and `completeSetup` goes straight to finished.
 * The visual language — breadcrumb, folder rows with item counts, a docked selection bar — is what
 * survives, and is what this renders.
 */
@Composable
fun LibrarySetupPage(
    state: LibrarySetupUiState,
    onOpenFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onToggleFolder: (String) -> Unit,
    onComplete: () -> Unit,
    onDismissError: () -> Unit,
) {
    Div(attrs = { classes("lsetup") }) {
        Div(attrs = { classes("lsetup-head") }) {
            H1(attrs = { classes("lsetup-t") }) { Text("Choose your audiobook folders") }
            P(attrs = { classes("lsetup-sub") }) {
                Text("These folders are on the machine running your ListenUp server.")
            }
        }

        state.error?.let { message ->
            Div(attrs = {
                classes("lsetup-err")
                attr("role", "alert")
            }) {
                Span(attrs = { classes("lsetup-err-t") }) { Text(message) }
                Button(attrs = {
                    classes("lsetup-err-x")
                    attr("type", TYPE_BUTTON)
                    attr("aria-label", "Dismiss")
                    onClick { onDismissError() }
                }) { Icon(WebIcon.X, size = DISMISS_ICON_SIZE) }
            }
        }

        Breadcrumb(state, onNavigateUp)

        when {
            state.isCheckingStatus || state.isLoadingDirectories -> {
                Div(attrs = { classes("skel", "lsetup-skel") })
            }

            state.directories.isEmpty() -> {
                Div(attrs = { classes("lsetup-empty") }) {
                    P { Text("Nothing in this folder. Go up and try another.") }
                }
            }

            else -> {
                Div(attrs = { classes("lsetup-list") }) {
                    state.directories.forEach { entry ->
                        FolderRow(
                            entry = entry,
                            selected = entry.path in state.selectedPaths,
                            onOpen = { onOpenFolder(entry.path) },
                            onToggle = { onToggleFolder(entry.path) },
                        )
                    }
                }
            }
        }

        SelectionBar(state, onComplete)
    }
}

/**
 * Where you are on the server, and the way back up.
 *
 * The whole crumb is one control rather than a per-segment trail. `LibrarySetupViewModel` offers
 * `navigateUp()` and `loadDirectory(path)` — it has no notion of jumping three levels at once, and
 * a crumb whose middle segments were clickable would be inventing one. Up is the movement the
 * ViewModel actually has, so up is what is offered.
 */
@Composable
private fun Breadcrumb(
    state: LibrarySetupUiState,
    onNavigateUp: () -> Unit,
) {
    Div(attrs = { classes("lsetup-crumb") }) {
        Button(attrs = {
            classes("lsetup-up")
            attr("type", TYPE_BUTTON)
            attr("aria-label", "Go up one folder")
            // At the root there is nowhere up to go, and `disabled` says that to a keyboard and a
            // screen reader as well as to an eye.
            if (state.isRoot || state.parentPath == null) attr("disabled", "")
            onClick { onNavigateUp() }
        }) { Icon(WebIcon.ChevronLeft, size = CRUMB_ICON_SIZE) }
        Span(attrs = { classes("lsetup-path", "mono") }) { Text(state.currentPath) }
    }
}

/**
 * One directory: what it is called, how much is in it, and whether it is one of the folders your
 * library will watch.
 *
 * Two controls, not one. Opening a folder and choosing it are different intentions — a reader
 * drilling down to find the right level must not add every folder they pass through — so the row
 * body navigates and the checkbox selects. A single tap that did both is the kind of shortcut that
 * silently adds four folders on the way to the fifth.
 */
@Composable
private fun FolderRow(
    entry: DirectoryEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    Div(attrs = {
        classes("lsetup-row")
        if (selected) classes("on")
    }) {
        // A real checkbox: reachable by Tab, togglable by Space, and announced as checked.
        Button(attrs = {
            classes("lsetup-check")
            attr("type", TYPE_BUTTON)
            attr("role", "checkbox")
            attr("aria-checked", selected.toString())
            attr("aria-label", "Add ${entry.name} to your library")
            onClick { onToggle() }
        }) { if (selected) Icon(WebIcon.Check, size = CHECK_ICON_SIZE) }

        Button(attrs = {
            classes("lsetup-open")
            attr("type", TYPE_BUTTON)
            // `hasChildren` false means there is nothing below this to browse into. The folder can
            // still be SELECTED — a leaf directory full of audiobooks is the common case — so only
            // the navigation half is unavailable.
            if (!entry.hasChildren) attr("disabled", "")
            onClick { onOpen() }
        }) {
            Div(attrs = { classes("lsetup-row-text") }) {
                Span(attrs = { classes("lsetup-row-n") }) { Text(entry.name) }
                Span(attrs = { classes("lsetup-row-c") }) { Text(itemCountLabel(entry.itemCount)) }
            }
            if (entry.hasChildren) Icon(WebIcon.ChevronRight, size = CHEVRON_ICON_SIZE)
        }
    }
}

/**
 * What you have chosen, and the button that commits it.
 *
 * Always present, even at zero, so the bar does not appear and shove the list up the moment the
 * first folder is ticked. Continue is `disabled` until something is selected — the ViewModel would
 * refuse with an error message anyway, and a button that exists only to tell you off is worse than
 * one that plainly cannot be pressed yet.
 */
@Composable
private fun SelectionBar(
    state: LibrarySetupUiState,
    onComplete: () -> Unit,
) {
    val count = state.selectedPaths.size
    Div(attrs = { classes("lsetup-bar") }) {
        Span(attrs = { classes("lsetup-count") }) { Text(selectionLabel(count)) }
        Button(attrs = {
            classes("btn-c", "lsetup-go")
            attr("type", TYPE_BUTTON)
            if (count == 0 || state.isCreatingLibrary) attr("disabled", "")
            onClick { onComplete() }
        }) {
            Text(if (state.isCreatingLibrary) "Setting up…" else "Continue")
        }
    }
}

/** "1 item" vs "248 items" — a folder holding one thing is real, so the plural is never assumed. */
internal fun itemCountLabel(count: Int): String = if (count == 1) "1 item" else "$count items"

/** "No folders chosen" / "1 folder chosen" / "3 folders chosen". */
internal fun selectionLabel(count: Int): String =
    when (count) {
        0 -> "No folders chosen"
        1 -> "1 folder chosen"
        else -> "$count folders chosen"
    }

/** The `type` every control here declares, so none of them submits anything. */
private const val TYPE_BUTTON = "button"

private const val DISMISS_ICON_SIZE = 15

private const val CRUMB_ICON_SIZE = 18

private const val CHECK_ICON_SIZE = 15

private const val CHEVRON_ICON_SIZE = 16
