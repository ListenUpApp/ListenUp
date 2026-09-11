package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.uploads.UploadedBook
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.pickedFiles
import com.calypsan.listenup.web.design.Panel
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

/**
 * Put books into the library from this machine.
 *
 * ⛔ **No "which book is this?" step, deliberately.** The client sends the structure the admin
 * picked and nothing more — deciding how many books a pile of files represents is the server's job,
 * where the audio tags already are, and guessing at it here would only produce a second, worse
 * answer to reconcile.
 */
@Composable
fun UploadPage(
    state: UploadBooksUiState,
    onFilesPicked: (List<File>) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("upl") }) {
        Breadcrumb(trail = listOf("Admin", "Upload books"), onNavigate = { onOpenAdmin() })
        H1(attrs = { classes("upl-t") }) { Text("Upload books") }

        when (state) {
            UploadBooksUiState.Idle -> {
                Picker(onFilesPicked)
            }

            is UploadBooksUiState.Uploading -> {
                Uploading(state, onCancel)
            }

            UploadBooksUiState.Finalizing -> {
                Progress(label = "Adding them to your library…", percent = null)
            }

            is UploadBooksUiState.Finished -> {
                Finished(state, onReset)
            }

            is UploadBooksUiState.Error -> {
                P(attrs = { classes("upl-err") }) { Text(state.error.message) }
                Button(attrs = {
                    classes("btn-c")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onReset() }
                }) { Text("Try again") }
            }
        }
    }
}

/**
 * The two picks, side by side.
 *
 * A folder pick carries each file's path within it, which is the structure the server groups on; a
 * loose-file pick carries none. Both are offered because both are how people actually keep books,
 * and a folder-only picker would make a single `.m4b` unimportable.
 */
@Composable
private fun Picker(onFilesPicked: (List<File>) -> Unit) {
    var folderInput by remember { mutableStateOf<HTMLInputElement?>(null) }
    var fileInput by remember { mutableStateOf<HTMLInputElement?>(null) }

    Panel(title = "Choose what to add") {
        P(attrs = { classes("upl-lede") }) {
            Text("Pick a book's folder, or the audio files themselves. The server works out what they are.")
        }
        Div(attrs = { classes("upl-picks") }) {
            Button(attrs = {
                classes("btn-c")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { folderInput?.click() }
            }) { Text("Choose a folder") }
            Button(attrs = {
                classes("btn-o")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { fileInput?.click() }
            }) { Text("Choose files") }
        }
        Input(type = InputType.File, attrs = {
            id("upl-folder")
            attr("aria-label", "Choose a folder")
            // ⛔ Not a Compose attribute — `webkitdirectory` has no typed builder, and it is the
            // only thing that makes the browser report each file's path within the pick.
            attr("webkitdirectory", "")
            attr("multiple", "")
            style { property("display", "none") }
            ref { element ->
                folderInput = element
                onDispose { folderInput = null }
            }
            onChange { event -> pickFrom(event.target as HTMLInputElement, onFilesPicked) }
        })
        Input(type = InputType.File, attrs = {
            id("upl-files")
            attr("aria-label", "Choose files")
            attr("multiple", "")
            attr("accept", AUDIO_ACCEPT)
            style { property("display", "none") }
            ref { element ->
                fileInput = element
                onDispose { fileInput = null }
            }
            onChange { event -> pickFrom(event.target as HTMLInputElement, onFilesPicked) }
        })
    }
}

private fun pickFrom(
    element: HTMLInputElement,
    onFilesPicked: (List<File>) -> Unit,
) {
    onFilesPicked(element.pickedFiles())
    // Re-picking the same folder must fire change again next time.
    element.value = ""
}

@Composable
private fun Uploading(
    state: UploadBooksUiState.Uploading,
    onCancel: () -> Unit,
) {
    Progress(
        label = "Sending ${state.filename} (${state.fileIndex + 1} of ${state.fileCount})",
        percent = state.fraction?.let { (it * PERCENT).toInt() },
    )
    Button(attrs = {
        classes("btn-o")
        attr(ATTR_TYPE, VALUE_BUTTON)
        onClick { onCancel() }
    }) { Text("Cancel") }
}

/**
 * ⛔ A null [percent] is an indeterminate bar, never a bar pinned at zero — a selection that could
 * not report its sizes is unknown progress, and a stuck bar reads as a hung upload.
 */
@Composable
private fun Progress(
    label: String,
    percent: Int?,
) {
    Div(attrs = {
        classes("upl-live")
        attr("role", "status")
        attr("aria-live", "polite")
    }) {
        P(attrs = { classes("upl-step") }) { Text(label) }
        Div(attrs = {
            classes("upl-bar")
            if (percent == null) classes("is-idle")
        }) {
            Div(attrs = {
                classes("upl-bar-fill")
                percent?.let { style { property("width", "$it%") } }
            })
        }
    }
}

/**
 * What landed, what was already here, and what did not make it.
 *
 * ⛔ Three groups, not a count and a failure list: **a duplicate is not a failure**, and saying so
 * is the difference between "you already have this" and "something went wrong".
 */
@Composable
private fun Finished(
    state: UploadBooksUiState.Finished,
    onReset: () -> Unit,
) {
    Div(attrs = { classes("upl-done") }) {
        P(attrs = { classes("upl-sum") }) { Text(finishedSummary(state)) }
        Group("Added", state.imported)
        Group("Already in your library", state.duplicates)
        Group("Couldn't be added", state.failed)
        Button(attrs = {
            classes("btn-c")
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { onReset() }
        }) { Text("Add more") }
    }
}

@Composable
private fun Group(
    heading: String,
    books: List<UploadedBook>,
) {
    if (books.isEmpty()) return
    Panel(title = "$heading (${books.size})") {
        books.forEach { book ->
            Div(attrs = { classes("upl-row") }) {
                Span(attrs = { classes("upl-n") }) { Text(book.title) }
                book.detail?.let { detail -> Span(attrs = { classes("upl-d") }) { Text(detail) } }
            }
        }
    }
}

/**
 * One sentence for the whole run.
 *
 * ⛔ Reads as what happened, not as a tally of three numbers: a run where everything was already
 * present is a complete, successful outcome and must not lead with "0 added".
 */
internal fun finishedSummary(state: UploadBooksUiState.Finished): String {
    val added = state.imported.size
    val dupes = state.duplicates.size
    val failed = state.failed.size
    return when {
        added == 0 && failed == 0 && dupes > 0 -> {
            "Everything you picked was already in your library."
        }

        added == 0 && dupes == 0 && failed > 0 -> {
            "Nothing could be added."
        }

        else -> {
            buildString {
                append(if (added == 1) "1 book added" else "$added books added")
                if (dupes > 0) append(", $dupes already here")
                if (failed > 0) append(", $failed failed")
                append(".")
            }
        }
    }
}

private const val AUDIO_ACCEPT = ".m4b,.m4a,.mp3,.opus,.ogg,.flac,.aac,.wma,audio/*"

private const val PERCENT = 100

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"
