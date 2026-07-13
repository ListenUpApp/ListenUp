package com.calypsan.listenup.client.features.chaptereditor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.model.Chapter
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_detail_panel_title
import listenup.composeapp.generated.resources.chapter_editor_field_start_time
import listenup.composeapp.generated.resources.chapter_editor_field_start_time_hint
import listenup.composeapp.generated.resources.chapter_editor_field_title
import listenup.composeapp.generated.resources.chapter_editor_snap_to_playhead

/**
 * Detail panel for the selected [chapter] — title, keyboard-precision start time, and a
 * snap-to-playhead shortcut. Docked permanently at [com.calypsan.listenup.client.design.TwoPaneMinWidth]+
 * width and presented as a bottom sheet below it; the caller owns that placement decision, this
 * composable only renders the panel's content.
 *
 * The start-time field is keyed on [Chapter.id] + [Chapter.startTime] so an external retime (drift
 * correction, undo) resyncs the displayed text; an in-progress edit is only ever lost by an
 * external change landing mid-edit, which is the same trade-off [MarkerLaneTimeline] itself makes
 * (see its `PrecisionHud`).
 *
 * @param onCommitStartTime Fired once, on IME "Done", with the parsed millisecond value. Malformed
 *   text is silently ignored (the field simply doesn't commit) — never a crash, never a partial write.
 * @param onSnapToPlayhead Reads the current playback position the deferred-lambda way and retimes
 *   [chapter] to it in one step; the caller supplies the read + the VM call together.
 */
@Composable
fun ChapterDetailPanel(
    chapter: Chapter,
    onTitleChange: (String) -> Unit,
    onCommitStartTime: (Long) -> Unit,
    onSnapToPlayhead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var startTimeText by
        remember(chapter.id, chapter.startTime) { mutableStateOf(formatPreciseTime(chapter.startTime)) }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.chapter_editor_detail_panel_title),
            style = MaterialTheme.typography.titleMedium,
        )

        ListenUpTextField(
            value = chapter.title,
            onValueChange = onTitleChange,
            label = stringResource(Res.string.chapter_editor_field_title),
        )

        ListenUpTextField(
            value = startTimeText,
            onValueChange = { startTimeText = it },
            label = stringResource(Res.string.chapter_editor_field_start_time),
            placeholder = stringResource(Res.string.chapter_editor_field_start_time_hint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        parsePreciseTime(startTimeText)?.let(onCommitStartTime)
                    },
                ),
        )

        OutlinedButton(onClick = onSnapToPlayhead) {
            Text(stringResource(Res.string.chapter_editor_snap_to_playhead))
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val MILLIS_PER_MINUTE = MILLIS_PER_SECOND * SECONDS_PER_MINUTE
private const val MILLIS_PER_HOUR = MILLIS_PER_MINUTE * MINUTES_PER_HOUR

// `groupValues` index for each capture in the strict `HH:MM:SS.mmm` parse regex (index 0 is the
// whole match). Named so the parse reads as field access, not indexing into an opaque list.
private const val GROUP_HOURS = 1
private const val GROUP_MINUTES = 2
private const val GROUP_SECONDS = 3
private const val GROUP_MILLIS = 4

/** Formats [ms] as `HH:MM:SS.mmm` — mirrors `MarkerLaneTimeline`'s `PrecisionHud` format. */
private fun formatPreciseTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val hours = clamped / MILLIS_PER_HOUR
    val minutes = clamped % MILLIS_PER_HOUR / MILLIS_PER_MINUTE
    val seconds = clamped % MILLIS_PER_MINUTE / MILLIS_PER_SECOND
    val millis = clamped % MILLIS_PER_SECOND
    return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
}

/** Parses a strict `HH:MM:SS.mmm` string back to milliseconds; `null` on any malformed input. */
private fun parsePreciseTime(text: String): Long? {
    val match = Regex("""^(\d{1,2}):(\d{2}):(\d{2})\.(\d{3})$""").matchEntire(text.trim()) ?: return null
    val values = match.groupValues
    val hours = values[GROUP_HOURS].toLong()
    val minutes = values[GROUP_MINUTES].toLong()
    val seconds = values[GROUP_SECONDS].toLong()
    val millis = values[GROUP_MILLIS].toLong()
    return hours * MILLIS_PER_HOUR + minutes * MILLIS_PER_MINUTE + seconds * MILLIS_PER_SECOND + millis
}
