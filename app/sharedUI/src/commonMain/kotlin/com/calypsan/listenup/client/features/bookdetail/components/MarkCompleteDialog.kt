package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.client.presentation.bookdetail.FinishDates
import com.calypsan.listenup.client.presentation.bookdetail.FinishDatesProblem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_finish_dates_before_start
import listenup.composeapp.generated.resources.book_detail_finish_dates_in_future
import listenup.composeapp.generated.resources.book_detail_finished
import listenup.composeapp.generated.resources.book_detail_mark_as_finished
import listenup.composeapp.generated.resources.book_detail_started
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_ok
import listenup.composeapp.generated.resources.common_select_date

/**
 * Asks when the reader started and finished a book before marking it finished.
 *
 * Mostly for logging books read before ListenUp: the form opens on the recorded start day (today if
 * there is none) and today, so confirming without touching it records exactly what a one-tap finish
 * would. Days are held as calendar days in [FinishDates] and only become instants at confirm, in the
 * reader's own [timeZone] — the dialog used to keep the picker's UTC-midnight millis, which read
 * back as the day before anywhere west of Greenwich.
 *
 * Neither day may be in the future (the pickers refuse them), and the finish may not precede the
 * start (the form says so and holds the confirm).
 *
 * @param startedAtMs Existing start date in epoch milliseconds (from playback position)
 * @param onConfirm Called with (startedAtMs, finishedAtMs) when user confirms
 * @param onDismiss Called when dialog is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkCompleteDialog(
    startedAtMs: Long?,
    onConfirm: (startedAt: Long?, finishedAt: Long) -> Unit,
    onDismiss: () -> Unit,
    nowMs: Long = remember { currentEpochMilliseconds() },
    timeZone: TimeZone = remember { TimeZone.currentSystemDefault() },
) {
    val opened = remember(startedAtMs, nowMs, timeZone) { FinishDates.initial(startedAtMs, nowMs, timeZone) }
    val today = opened.finished

    var dates by remember(opened) { mutableStateOf(opened) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showFinishDatePicker by remember { mutableStateOf(false) }

    val problem = dates.problem(today)

    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.book_detail_mark_as_finished),
        confirmText = stringResource(Res.string.book_detail_mark_as_finished),
        onConfirm = {
            val stamps = dates.toTimestamps(startedAtMs, nowMs, timeZone)
            onConfirm(stamps.startedAtMs, stamps.finishedAtMs)
        },
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
        confirmEnabled = problem == null,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DateField(
                label = stringResource(Res.string.book_detail_started),
                date = dates.started,
                timeZone = timeZone,
                onClick = { showStartDatePicker = true },
            )
            DateField(
                label = stringResource(Res.string.book_detail_finished),
                date = dates.finished,
                timeZone = timeZone,
                onClick = { showFinishDatePicker = true },
            )
            problem?.let { dateProblem ->
                Text(
                    text = stringResource(dateProblem.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showStartDatePicker) {
        DatePickerDialogWrapper(
            initialDate = dates.started,
            latestDate = today,
            onDateSelected = { date ->
                dates = dates.copy(started = date)
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }

    if (showFinishDatePicker) {
        DatePickerDialogWrapper(
            initialDate = dates.finished,
            latestDate = today,
            onDateSelected = { date ->
                dates = dates.copy(finished = date)
                showFinishDatePicker = false
            },
            onDismiss = { showFinishDatePicker = false },
        )
    }
}

private val FinishDatesProblem.message: StringResource
    get() =
        when (this) {
            FinishDatesProblem.FinishedBeforeStarted -> Res.string.book_detail_finish_dates_before_start
            FinishDatesProblem.InTheFuture -> Res.string.book_detail_finish_dates_in_future
        }

/**
 * Read-only text field that displays a date and opens a picker on tap.
 */
@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    timeZone: TimeZone,
    onClick: () -> Unit,
) {
    val displayText = formatDateLong(date.atStartOfDayIn(timeZone).toEpochMilliseconds())

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = stringResource(Res.string.common_select_date),
                )
            },
        )
        // Invisible overlay to intercept clicks
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClick() },
        )
    }
}

/**
 * Wrapper around Material 3 DatePickerDialog.
 *
 * The M3 picker speaks UTC-midnight millis for a calendar day, so the day goes in and comes out
 * through UTC and nowhere else — the reader's zone is applied once, at confirm, by [FinishDates].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogWrapper(
    initialDate: LocalDate,
    latestDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val latestMillis = latestDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            yearRange = DatePickerDefaults.YearRange.first..latestDate.year,
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= latestMillis

                    override fun isSelectableYear(year: Int): Boolean = year <= latestDate.year
                },
        )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { selectedMillis ->
                        // Inside the let: OK with nothing selected commits nothing.
                        haptics.commit()
                        onDateSelected(FinishDates.dayOf(selectedMillis, TimeZone.UTC))
                    }
                },
            ) {
                Text(stringResource(Res.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptics.press()
                    onDismiss()
                },
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        shape = MaterialTheme.shapes.large,
    ) {
        DatePicker(state = datePickerState)
    }
}
