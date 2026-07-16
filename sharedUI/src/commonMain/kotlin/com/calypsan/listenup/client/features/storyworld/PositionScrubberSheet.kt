package com.calypsan.listenup.client.features.storyworld

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.Chapter
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_done
import listenup.composeapp.generated.resources.story_world_reveal_after

private val TRACK_HEIGHT = 6.dp
private val THUMB_SIZE = 28.dp
private val TICK_WIDTH = 2.dp
private val TICK_HEIGHT = 12.dp
private val TRACK_TOUCH_HEIGHT = 40.dp
private val BUBBLE_ROW_HEIGHT = 28.dp
private val BUBBLE_HALF_WIDTH = 40.dp

/**
 * Bottom sheet for scrubbing to a custom `(book, position)` anchor. Pure UI — the host owns book
 * selection, chapter loading, and the actual position value; this sheet only renders and reports
 * gestures against them.
 *
 * The scrubber track is a self-contained implementation rather than a reuse of
 * `design/timeline/MarkerLaneTimeline` — see this file's own KDoc on [PositionScrubber] for why.
 *
 * @param books The world's books, for the book-switcher dropdown.
 * @param selectedBookId The book [positionMs] is relative to.
 * @param chapters [selectedBookId]'s chapters (may be empty — the scrubber renders with no ticks).
 * @param positionMs The in-progress scrub position, in milliseconds from the book's start.
 * @param confirmLabel Localized "Visible after &lt;label&gt;" caption for the confirm bar, resolved by the host.
 * @param onBookSelected Called when a different book is chosen from the dropdown.
 * @param onPositionChange Called continuously as the user drags or taps the track.
 * @param onConfirm Commits [positionMs] against [selectedBookId] as the anchor.
 * @param onDismiss Dismisses the sheet without committing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionScrubberSheet(
    books: List<AnchorBook>,
    selectedBookId: String,
    chapters: List<Chapter>,
    positionMs: Long,
    confirmLabel: String,
    onBookSelected: (String) -> Unit,
    onPositionChange: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedBook = books.firstOrNull { it.id == selectedBookId }
    val durationMs = selectedBook?.durationMs ?: 0L

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(Res.string.story_world_reveal_after),
                style = MaterialTheme.typography.titleLarge,
            )

            BookSelectorRow(books = books, selectedBook = selectedBook, onBookSelected = onBookSelected)

            PositionScrubber(
                chapters = chapters,
                durationMs = durationMs,
                positionMs = positionMs,
                onPositionChange = onPositionChange,
            )

            ElapsedRow(positionMs = positionMs, durationMs = durationMs)

            ConfirmBar(confirmLabel = confirmLabel, onConfirm = onConfirm)
        }
    }
}

/** Book-switcher button + dropdown; shows the selected book's title. */
@Composable
private fun BookSelectorRow(
    books: List<AnchorBook>,
    selectedBook: AnchorBook?,
    onBookSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedBook?.title.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            books.forEach { book ->
                DropdownMenuItem(
                    text = { Text(listOfNotNull(book.sequenceLabel, book.title).joinToString(" · ")) },
                    onClick = {
                        expanded = false
                        onBookSelected(book.id)
                    },
                )
            }
        }
    }
}

/**
 * A compact position scrubber: a filled track with chapter tick marks and a draggable thumb, plus
 * a floating bubble label showing the containing chapter's title (or the elapsed time, absent
 * chapters).
 *
 * **Not built on `design/timeline/MarkerLaneTimeline`.** That component is the chapter-editor's
 * marker-repositioning timeline, and its public API doesn't fit this sheet's much simpler needs:
 * every lane row unconditionally renders a `TimelineMinimap` plus pinch/double-tap zoom gestures —
 * chrome built for the full chapter-editing workflow, wrong for a one-value position picker in a
 * bottom sheet. Its `TimelineAxis` (the only part that draws a horizontal line) renders just a
 * 2dp playhead tick on a 24dp strip with tap-only `onSeek` — no filled progress track, no
 * draggable thumb, no bubble label, and no drag-to-scrub gesture at all (dragging is wired only to
 * lane markers via `negotiatedDrag`, which a read-only chapter lane explicitly refuses via
 * `canDrag = false`). Reaching the requested visual (6dp filled track, 28dp thumb, floating bubble,
 * continuous drag-to-scrub) would mean discarding the axis and negotiator entirely and routing a
 * static, one-shot list of chapter ticks through `MarkerLane`'s push-based `Flow<List<TimeMarker>>`
 * contract for no benefit — so this scrubber is a small self-contained implementation instead.
 */
@Composable
private fun PositionScrubber(
    chapters: List<Chapter>,
    durationMs: Long,
    positionMs: Long,
    onPositionChange: (Long) -> Unit,
) {
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val safeDurationMs = durationMs.coerceAtLeast(1L)
    val fraction = (positionMs.toFloat() / safeDurationMs).coerceIn(0f, 1f)

    fun pxToMs(px: Float): Long =
        if (trackWidthPx <= 0f) {
            0L
        } else {
            ((px / trackWidthPx) * safeDurationMs).toLong().coerceIn(0L, durationMs)
        }

    fun msToFraction(ms: Long): Float = (ms.toFloat() / safeDurationMs).coerceIn(0f, 1f)

    val bubbleText =
        remember(chapters, positionMs) {
            chapters
                .lastOrNull { it.startTime <= positionMs }
                ?.takeIf { positionMs < it.startTime + it.duration }
                ?.title ?: DurationFormatter.hoursMinutes(positionMs.milliseconds)
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(BUBBLE_ROW_HEIGHT)) {
            Box(
                modifier =
                    Modifier
                        .offset {
                            val bubbleX = (fraction * trackWidthPx) - BUBBLE_HALF_WIDTH.toPx()
                            IntOffset(bubbleX.toInt().coerceAtLeast(0), 0)
                        }.clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = bubbleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TRACK_TOUCH_HEIGHT)
                    .onSizeChanged { trackWidthPx = it.width.toFloat() }
                    .pointerInput(durationMs) {
                        detectTapGestures { offset -> onPositionChange(pxToMs(offset.x)) }
                    }.pointerInput(durationMs) {
                        detectDragGestures { change, _ -> onPositionChange(pxToMs(change.position.x)) }
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(TRACK_HEIGHT)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(fraction = fraction)
                        .height(TRACK_HEIGHT)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.primary),
            )
            chapters.forEach { chapter ->
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset {
                                IntOffset((msToFraction(chapter.startTime) * trackWidthPx).toInt(), 0)
                            }.size(width = TICK_WIDTH, height = TICK_HEIGHT)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset {
                            IntOffset((fraction * trackWidthPx - THUMB_SIZE.toPx() / 2f).toInt(), 0)
                        }.size(THUMB_SIZE)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Elapsed/total captions flanking the current position's percentage through the book. */
@Composable
private fun ElapsedRow(
    positionMs: Long,
    durationMs: Long,
) {
    val start = DurationFormatter.hoursMinutes(0L.milliseconds)
    val elapsed = DurationFormatter.hoursMinutes(positionMs.milliseconds)
    val total = DurationFormatter.hoursMinutes(durationMs.milliseconds)
    val percent = if (durationMs > 0) positionMs * 100 / durationMs else 0L

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = start,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = "$elapsed · $percent%", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = total,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tertiary-tinted preview of the resulting anchor caption, plus the full-width confirm action. */
@Composable
private fun ConfirmBar(
    confirmLabel: String,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = confirmLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
        }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.common_done))
        }
    }
}
