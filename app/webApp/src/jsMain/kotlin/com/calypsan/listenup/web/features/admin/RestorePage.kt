package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.web.design.ConfirmDialog
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Restore — the most destructive thing this app can do, on a page of its own.
 *
 * Pure in [state] and [progress]; the store wiring lives one level up.
 *
 * ⛔ **The confirmation is the ViewModel's state, not a dialog this page opens.** `Confirming` is a
 * value in [RestoreBackupUiState], so pressing Restore is a request the ViewModel grants — which is
 * what makes the decision survivable: the page cannot start a restore by rendering, and a reload
 * mid-decision lands back on Idle rather than half-committed.
 *
 * **Progress is narrated, not measured.** The server streams named phases — draining, swapping,
 * migrating — and there is no honest percentage to put on them, so the page says which phase it is
 * in. A bar that moved at a rate nobody could predict would be an invention.
 */
@Composable
fun RestorePage(
    state: RestoreBackupUiState,
    progress: BackupEvent?,
    onRequest: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onOpenBackups: () -> Unit,
) {
    Div(attrs = { classes("rst") }) {
        // Absent while a restore is running: the server is swapping its own database out, and a
        // link away from the only page narrating that is an invitation to miss the outcome.
        if (state !is RestoreBackupUiState.Restoring) {
            Button(attrs = {
                classes("btn-o", "rst-back")
                attr("type", VALUE_BUTTON)
                onClick { onOpenBackups() }
            }) { Text("← Backups") }
        }

        H1(attrs = { classes("rst-title") }) { Text("Restore from a backup") }

        when (state) {
            is RestoreBackupUiState.Idle -> {
                state.error?.let { error ->
                    P(attrs = {
                        classes("rst-err")
                        attr("role", "alert")
                    }) { Text(error.message) }
                }
                P(attrs = { classes("rst-body") }) {
                    Text(
                        "Restoring replaces this server's whole database with the one in the backup. " +
                            "Anything added since it was taken is gone — books, listeners, progress, all of it.",
                    )
                }
                Button(attrs = {
                    classes("btn-c")
                    attr("type", VALUE_BUTTON)
                    onClick { onRequest() }
                }) { Text("Restore this backup") }
            }

            RestoreBackupUiState.Confirming -> {
                P(attrs = { classes("rst-body") }) { Text("Waiting for you to confirm.") }
                ConfirmDialog(
                    open = true,
                    title = "Replace everything with this backup?",
                    body =
                        "Every listener's progress since this backup was taken will be lost, and they " +
                            "will not be told. There is no undo.",
                    confirmLabel = "Replace everything",
                    onConfirm = onConfirm,
                    onDismiss = onCancel,
                )
            }

            RestoreBackupUiState.Restoring -> {
                Div(attrs = {
                    classes("rst-live")
                    // The phase changes under the reader without them acting, so it has to be
                    // announced — politely, since a restore is not an error.
                    attr("role", "status")
                    attr("aria-live", "polite")
                }) {
                    Div(attrs = { classes("skel", "rst-bar") })
                    P(attrs = { classes("rst-phase") }) { Text(phaseLabel(progress)) }
                }
                P(
                    attrs = { classes("rst-body") },
                ) { Text("Leave this page open. The server is offline until it finishes.") }
            }

            is RestoreBackupUiState.Completed -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("Restored") }
                    P {
                        Text(
                            if (state.result.includedImages) {
                                "The library and its covers are back as they were."
                            } else {
                                "The library is back as it was. This backup carried no covers, so those are unchanged."
                            },
                        )
                    }
                    // The schema pair is the one detail worth surfacing: a restore that migrated
                    // across versions is the case where something might behave differently after.
                    if (state.result.schemaMigratedFrom != state.result.schemaMigratedTo) {
                        P(attrs = { classes("rst-schema") }) {
                            Text(
                                "The backup was migrated from schema ${state.result.schemaMigratedFrom} " +
                                    "to ${state.result.schemaMigratedTo}.",
                            )
                        }
                    }
                    Button(attrs = {
                        classes("btn-c")
                        attr("type", VALUE_BUTTON)
                        onClick { onOpenBackups() }
                    }) { Text("Back to backups") }
                }
            }
        }
    }
}

/**
 * What the server is doing right now, in the reader's terms.
 *
 * Every phase gets a sentence, including the two failures — a restore that rolled back has to say
 * so on this page, because it is the only place anyone is watching.
 */
private fun phaseLabel(event: BackupEvent?): String =
    when (event) {
        null -> "Starting…"

        BackupEvent.Validating -> "Checking the archive is whole…"

        BackupEvent.Draining -> "Waiting for in-flight requests to finish…"

        BackupEvent.Swapping -> "Swapping the database in…"

        BackupEvent.Migrating -> "Bringing the database up to date…"

        is BackupEvent.RestoreComplete -> "Done."

        is BackupEvent.RolledBack -> "It failed and the old database was put back: ${event.reason}"

        // The create-side phases, which a restore never emits. Named rather than left to an else,
        // so a new event added to the contract fails this `when` instead of silently reading
        // "Working…" forever.
        BackupEvent.DbSnapshotting -> WORKING

        is BackupEvent.ImagesCopying -> WORKING

        BackupEvent.Finalizing -> WORKING

        is BackupEvent.Created -> WORKING
    }

/** What the create-side phases would say if a restore ever emitted one. It does not. */
private const val WORKING = "Working…"

private const val VALUE_BUTTON = "button"
