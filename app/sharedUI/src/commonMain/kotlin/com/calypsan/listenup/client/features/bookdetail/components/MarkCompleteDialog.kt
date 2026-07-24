package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.calypsan.listenup.client.util.formatDateLong
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_mark_as_read
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_ok
import listenup.composeapp.generated.resources.common_select_date

/**
 * Dialog for marking a book as read with start and end date pickers.
 *
 * Allows users to record historical reading dates when marking a book complete.
 * Pre-populates start date from existing progress if available.
 *
 * @param startedAtMs Existing start date in epoch milliseconds (from playback position)
 * @param onConfirm Called with (startedAtMs, finishedAtMs) when user confirms
 * @param onDismiss Called when dialog is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkCompleteDialog(
    startedAtMs: Long?,
    onConfirm: (startedAt: Long, finishedAt: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = remember { currentEpochMilliseconds() }

    // Initial values: startedAt from existing progress or today, finishedAt = today
    val initialStartMillis = startedAtMs ?: now
    val initialFinishMillis = now

    var startDateMillis by remember { mutableStateOf(initialStartMillis) }
    var finishDateMillis by remember { mutableStateOf(initialFinishMillis) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showFinishDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(Res.string.book_detail_mark_as_read)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DateField(
                    label = "Started",
                    millis = startDateMillis,
                    onClick = { showStartDatePicker = true },
                )
                DateField(
                    label = "Finished",
                    millis = finishDateMillis,
                    onClick = { showFinishDatePicker = true },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(startDateMillis, finishDateMillis) }) {
                Text(stringResource(Res.string.book_detail_mark_as_read))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )

    if (showStartDatePicker) {
        DatePickerDialogWrapper(
            initialMillis = startDateMillis,
            onDateSelected = { millis ->
                startDateMillis = millis
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }

    if (showFinishDatePicker) {
        DatePickerDialogWrapper(
            initialMillis = finishDateMillis,
            onDateSelected = { millis ->
                finishDateMillis = millis
                showFinishDatePicker = false
            },
            onDismiss = { showFinishDatePicker = false },
        )
    }
}

/**
 * Read-only text field that displays a date and opens a picker on tap.
 */
@Composable
private fun DateField(
    label: String,
    millis: Long,
    onClick: () -> Unit,
) {
    val displayText = formatDateLong(millis)

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(4.dp),
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogWrapper(
    initialMillis: Long,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // Convert to UTC start-of-day for the picker
    val initialDate = epochMillisToLocalDate(initialMillis)
    val initialSelectionMillis = initialDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = initialSelectionMillis,
        )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { selectedMillis ->
                        onDateSelected(selectedMillis)
                    }
                },
            ) {
                Text(stringResource(Res.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        shape = MaterialTheme.shapes.large,
    ) {
        DatePicker(state = datePickerState)
    }
}

private fun epochMillisToLocalDate(millis: Long): LocalDate =
    Instant
        .fromEpochMilliseconds(millis)
        .toLocalDateTime(TimeZone.UTC)
        .date
