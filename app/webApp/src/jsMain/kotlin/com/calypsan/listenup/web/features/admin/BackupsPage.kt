package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

/**
 * Backups — take one, take one away, take one off the server, or put one back.
 *
 * Pure in [state] and [uploadState]; the store wiring lives one level up.
 *
 * **Restoring is not offered from here.** A row's Restore is a link to `/admin/backups/{id}/restore`
 * rather than a button that starts one, because a restore replaces the whole database and the
 * confirmation for that deserves its own page rather than a dialog over a list. The same is true of
 * an uploaded file: it becomes a staged backup and the route takes over.
 */
@Composable
fun BackupsPage(
    state: AdminBackupUiState,
    uploadState: RestoreFromFileUiState,
    onCreate: (includeImages: Boolean) -> Unit,
    onDownload: (BackupInfo) -> Unit,
    onAskDelete: (BackupInfo) -> Unit,
    onDismissDelete: () -> Unit,
    onDelete: (BackupInfo) -> Unit,
    onPickFile: (File) -> Unit,
    onResetUpload: () -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
    onRestore: (BackupInfo) -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("bkp") }) {
        Button(attrs = {
            classes("btn-o", "bkp-back")
            attr("type", VALUE_BUTTON)
            onClick { onOpenAdmin() }
        }) { Text("← Admin") }

        H1(attrs = { classes("bkp-title") }) { Text("Backups") }

        when (state) {
            AdminBackupUiState.Loading -> {
                Div(attrs = { classes("skel", "bkp-skel") })
            }

            is AdminBackupUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("Backups can't be shown") }
                    P { Text(state.error.message) }
                    Button(attrs = {
                        classes("btn-c")
                        attr("type", VALUE_BUTTON)
                        onClick { onRetry() }
                    }) { Text("Try again") }
                }
            }

            is AdminBackupUiState.Ready -> {
                ReadyContent(
                    state = state,
                    uploadState = uploadState,
                    onCreate = onCreate,
                    onDownload = onDownload,
                    onAskDelete = onAskDelete,
                    onDismissDelete = onDismissDelete,
                    onDelete = onDelete,
                    onPickFile = onPickFile,
                    onResetUpload = onResetUpload,
                    onClearError = onClearError,
                    onRestore = onRestore,
                )
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun ReadyContent(
    state: AdminBackupUiState.Ready,
    uploadState: RestoreFromFileUiState,
    onCreate: (Boolean) -> Unit,
    onDownload: (BackupInfo) -> Unit,
    onAskDelete: (BackupInfo) -> Unit,
    onDismissDelete: () -> Unit,
    onDelete: (BackupInfo) -> Unit,
    onPickFile: (File) -> Unit,
    onResetUpload: () -> Unit,
    onClearError: () -> Unit,
    onRestore: (BackupInfo) -> Unit,
) {
    // Whether the next backup carries the cover images. View-local: it is an argument to Create,
    // not a setting, and nothing outside this screen has an opinion about it.
    var includeImages by remember { mutableStateOf(true) }
    val uploading = uploadState as? RestoreFromFileUiState.Uploading

    state.error?.let { error ->
        BackupNotice(error.message, onClearError)
    }
    (uploadState as? RestoreFromFileUiState.Error)?.let { failed ->
        BackupNotice(failed.error.message, onResetUpload)
    }

    Div(attrs = { classes("bkp-make") }) {
        CheckboxField(
            label = "Include cover images",
            checked = includeImages,
            onChange = { includeImages = it },
            id = "bkp-images",
        )
        // The size difference is the whole reason this is a choice, so it says so.
        P(attrs = { classes("bkp-hint") }) {
            Text(
                "Covers are the bulk of an archive. Leave them out for a much smaller file you can restore the library from.",
            )
        }
        Div(attrs = { classes("bkp-make-acts") }) {
            Button(attrs = {
                classes("btn-c")
                attr("type", VALUE_BUTTON)
                disabledWhen(state.isCreating)
                onClick { onCreate(includeImages) }
            }) { Text(if (state.isCreating) "Backing up…" else "Back up now") }
            UploadButton(uploading = uploading, onPickFile = onPickFile)
        }
    }

    if (state.backups.isEmpty()) {
        Div(attrs = { classes("empty") }) {
            H3 { Text("No backups yet") }
            P { Text("A backup is a single file holding your library's database, and optionally its covers.") }
        }
    } else {
        Div(attrs = { classes("bkp-list") }) {
            state.backups.forEach { backup ->
                BackupRow(
                    backup = backup,
                    isDeleting = state.isDeleting,
                    onDownload = { onDownload(backup) },
                    onRestore = { onRestore(backup) },
                    onAskDelete = { onAskDelete(backup) },
                )
            }
        }
    }

    val pending = state.deleteConfirmBackup
    ConfirmDialog(
        open = pending != null,
        title = "Delete this backup?",
        body =
            "The archive from ${pending?.createdAt?.let(::formatWhen) ?: "this date"} will be removed " +
                "from the server. Your library is untouched — this deletes the copy, not the books.",
        confirmLabel = "Delete",
        onConfirm = { pending?.let(onDelete) },
        onDismiss = onDismissDelete,
    )
}

/**
 * Pick a `.listenup.zip` and send it up.
 *
 * A hidden file input driven by a real button, the same shape the cover and avatar pickers use — a
 * bare `<input type=file>` cannot be styled and announces itself as "Choose file", which says
 * nothing about what this one is for.
 */
@Composable
private fun UploadButton(
    uploading: RestoreFromFileUiState.Uploading?,
    onPickFile: (File) -> Unit,
) {
    var input by remember { mutableStateOf<HTMLInputElement?>(null) }

    Button(attrs = {
        classes("btn-o")
        attr("type", VALUE_BUTTON)
        disabledWhen(uploading != null)
        onClick { input?.click() }
    }) { Text(uploading?.let { "Uploading ${it.filename}…" } ?: "Restore from a file") }

    Input(type = InputType.File, attrs = {
        id("bkp-file-input")
        attr("accept", ".zip,application/zip")
        style { property("display", "none") }
        ref { element ->
            input = element
            onDispose { input = null }
        }
        onChange { event ->
            val element = event.target as HTMLInputElement
            element.files?.item(0)?.let(onPickFile)
            // Re-picking the same file must fire change again next time.
            element.value = ""
        }
    })
}

@Composable
private fun BackupRow(
    backup: BackupInfo,
    isDeleting: Boolean,
    onDownload: () -> Unit,
    onRestore: () -> Unit,
    onAskDelete: () -> Unit,
) {
    Div(attrs = { classes("bkp-row") }) {
        Div(attrs = { classes("bkp-row-t") }) {
            Span(attrs = { classes("bkp-when") }) { Text(formatWhen(backup.createdAt)) }
            Span(attrs = { classes("bkp-size") }) { Text(backup.sizeFormatted) }
        }
        Button(attrs = {
            classes("iconbtn", "bkp-act")
            attr("type", VALUE_BUTTON)
            attr("aria-label", "Download the backup from ${formatWhen(backup.createdAt)}")
            attr("title", "Download")
            onClick { onDownload() }
        }) { Icon(WebIcon.Download, size = SMALL_ICON) }
        Button(attrs = {
            classes("btn-o", "bkp-restore")
            attr("type", VALUE_BUTTON)
            onClick { onRestore() }
        }) { Text("Restore") }
        Button(attrs = {
            classes("iconbtn", "bkp-act")
            attr("type", VALUE_BUTTON)
            attr("aria-label", "Delete the backup from ${formatWhen(backup.createdAt)}")
            attr("title", "Delete")
            disabledWhen(isDeleting)
            onClick { onAskDelete() }
        }) { Icon(WebIcon.Trash, size = SMALL_ICON) }
    }
}

/** A dismissible line above the list: something the last action failed to do. */
@Composable
private fun BackupNotice(
    message: String,
    onDismiss: () -> Unit,
) {
    Div(attrs = { classes("bkp-err") }) {
        P(attrs = {
            classes("bkp-err-t")
            attr("role", "alert")
        }) { Text(message) }
        Button(attrs = {
            classes("bkp-err-x")
            attr("type", VALUE_BUTTON)
            attr("aria-label", "Dismiss")
            onClick { onDismiss() }
        }) { Icon(WebIcon.X, size = SMALL_ICON) }
    }
}

/**
 * When a backup was taken, to the minute.
 *
 * Backups are routinely taken several times a day, so the date alone cannot tell two apart — which
 * is the whole job of this label on a row you are about to delete or restore from.
 */
internal fun formatWhen(timestamp: Timestamp): String = localeDateTime(timestamp.epochMillis.toDouble())

/** `Date#toLocaleString`, which Kotlin/JS's own `Date` facade does not expose. */
private fun localeDateTime(epochMillis: Double): String =
    kotlin.js
        .Date(epochMillis)
        .asDynamic()
        .toLocaleString() as String

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16
