package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.FinishDates
import com.calypsan.listenup.client.presentation.bookdetail.FinishDatesProblem
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.web.design.DateField
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.ModalDialog
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Asks when the reader started and finished a book before marking it finished.
 *
 * ⛔ Web used to finish a book at "now" without asking, so a reader logging a book they read years
 * ago had to do it from their phone. This is Android's dialog: the same two days, the same defaults
 * (the recorded start day, or today; and today), the same rule — neither day in the future, the
 * finish not before the start — because all three come from the shared [FinishDates].
 *
 * The days are held as the `<input type=date>` strings and become instants only at confirm, in the
 * reader's own [timeZone]; confirming untouched sends exactly the recorded start and [nowMs].
 */
@Composable
fun MarkFinishedDialog(
    open: Boolean,
    startedAtMs: Long?,
    onConfirm: (startedAt: Long?, finishedAt: Long) -> Unit,
    onDismiss: () -> Unit,
    nowMs: Long = remember(open) { currentEpochMilliseconds() },
    timeZone: TimeZone = remember { TimeZone.currentSystemDefault() },
) {
    if (!open) return
    val opened = remember(startedAtMs, nowMs, timeZone) { FinishDates.initial(startedAtMs, nowMs, timeZone) }
    val today = opened.finished.toString()

    var started by remember(opened) { mutableStateOf(opened.started.toString()) }
    var finished by remember(opened) { mutableStateOf(opened.finished.toString()) }

    val dates = parseDay(started)?.let { s -> parseDay(finished)?.let { f -> FinishDates(s, f) } }
    val problem = dates?.problem(opened.finished)

    ModalDialog(open = true, title = "Mark as finished", onDismiss = onDismiss) {
        DateField(label = "Started", value = started, onInput = { started = it }, max = today)
        DateField(label = "Finished", value = finished, onInput = { finished = it }, max = today)
        problem?.let { reason ->
            P(attrs = {
                classes("f-err")
                attr("role", "alert")
            }) { Text(reason.message) }
        }
        DialogActions(
            confirmLabel = "Mark as finished",
            onConfirm = {
                val stamps = dates?.toTimestamps(startedAtMs, nowMs, timeZone) ?: return@DialogActions
                onConfirm(stamps.startedAtMs, stamps.finishedAtMs)
            },
            onDismiss = onDismiss,
            confirmEnabled = dates != null && problem == null,
        )
    }
}

/** A `yyyy-mm-dd` from a date input, or null for a cleared or half-typed one. */
private fun parseDay(value: String): LocalDate? = LocalDate.Formats.ISO.parseOrNull(value)

/** The English text of en.json's `book.detail_finish_dates_*`, the words Android and iOS show. */
private val FinishDatesProblem.message: String
    get() =
        when (this) {
            FinishDatesProblem.FinishedBeforeStarted -> "The finish date can’t be before the start date."
            FinishDatesProblem.InTheFuture -> "These dates can’t be in the future."
        }
