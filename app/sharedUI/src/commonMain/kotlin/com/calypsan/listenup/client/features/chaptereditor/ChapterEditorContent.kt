package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import com.calypsan.listenup.client.design.components.ListenUpTextField
import listenup.composeapp.generated.resources.chapter_editor_jump_to_title
import listenup.composeapp.generated.resources.chapter_editor_no_matches
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.design.timeline.ChapterDetailLane
import com.calypsan.listenup.client.design.timeline.ChapterMiniMap
import com.calypsan.listenup.client.design.timeline.TimelineChapter
import com.calypsan.listenup.client.design.timeline.TimelineFileBoundary
import com.calypsan.listenup.client.design.timeline.TimelineGeometry
import com.calypsan.listenup.client.design.timeline.chapterDensity
import com.calypsan.listenup.client.domain.model.Chapter
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_detail_lane
import listenup.composeapp.generated.resources.chapter_editor_lane_description
import listenup.composeapp.generated.resources.chapter_editor_minimap_description
import listenup.composeapp.generated.resources.chapter_editor_whole_book
import listenup.composeapp.generated.resources.chapter_editor_zoom_hint
import org.jetbrains.compose.resources.stringResource

private val PANE_SHAPE = RoundedCornerShape(24.dp)
private val LIST_PANE_WIDTH = 480.dp
private const val MINIMAP_BUCKETS = 90

/**
 * A chapter, paired with the number it carries in the whole book.
 *
 * The pairing exists so filtering cannot renumber anything. The list's search narrows what is
 * shown, and numbering the *visible* rows would relabel chapter 213 as chapter 1 the moment
 * someone typed — so the number travels with the chapter rather than being recovered from its
 * position in whatever list is on screen.
 *
 * @property chapter the boundary itself.
 * @property number its 1-based position in the full, start-time-ordered set.
 */
data class NumberedChapter(
    val chapter: Chapter,
    val number: Int,
)

/**
 * Pairs every chapter with its true number, before any filtering happens.
 *
 * Call this on the full set and filter the result — never the other way round.
 */
fun List<Chapter>.numbered(): List<NumberedChapter> = mapIndexed { i, c -> NumberedChapter(c, i + 1) }

/**
 * The editor's two panes: the timeline you navigate with, and the list you edit from.
 *
 * Side by side when there is room, stacked when there is not. The split is not cosmetic — the
 * spec is explicit that the list alone must be sufficient, with the timeline as "optional spatial
 * sugar", which is exactly what makes the stacked layout acceptable rather than a degraded one.
 *
 * @param chapters the working set, already numbered against the full book.
 * @param bookDurationMs the book's own duration, from the book — not the last chapter's end.
 * @param geometry the detail lane's current window.
 * @param isWide whether there is room for two panes.
 * @param selectedChapterId the boundary the list and lane share focus on.
 * @param playheadMs transport position, or null when nothing is playing.
 * @param onSelect focus a boundary.
 * @param onNudge move a boundary by a signed step.
 * @param onSnapToPlayhead take the playhead's exact millisecond.
 * @param onToggleLock pin a boundary against drift.
 * @param lockedChapterIds boundaries currently pinned, so the row's lock reads as state rather
 *   than as a button that does nothing visible.
 * @param query narrows the list only — the timeline keeps showing the whole book, because the lane
 *   is a picture of the audio and hiding parts of it would misrepresent what is there.
 * @param onQueryChange the search box changed.
 * @param onMore open a row's overflow.
 * @param onSeekFraction move the detail lane's window from the minimap.
 * @param onRetime a boundary was dragged to a new start.
 * @param modifier Modifier for the content.
 * @param contentPadding insets from the scaffold.
 * @param fileBoundaries read-only audio-file dividers.
 * @param ghosts drift preview positions, drawn alongside the current ones.
 */
@Composable
fun ChapterEditorContent(
    chapters: List<NumberedChapter>,
    bookDurationMs: Long,
    geometry: TimelineGeometry,
    isWide: Boolean,
    selectedChapterId: String?,
    playheadMs: Long?,
    onSelect: (String) -> Unit,
    onNudge: (String, Long) -> Unit,
    onSnapToPlayhead: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onMore: (String) -> Unit,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onRetime: (String, Long) -> Unit = { _, _ -> },
    lockedChapterIds: Set<String> = emptySet(),
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    fileBoundaries: List<TimelineFileBoundary> = emptyList(),
    ghosts: List<TimelineChapter> = emptyList(),
) {
    val timeline: @Composable (Modifier) -> Unit = { paneModifier ->
        TimelinePane(
            chapters = chapters,
            bookDurationMs = bookDurationMs,
            geometry = geometry,
            selectedChapterId = selectedChapterId,
            playheadMs = playheadMs,
            onSeekFraction = onSeekFraction,
            fileBoundaries = fileBoundaries,
            ghosts = ghosts,
            lockedChapterIds = lockedChapterIds,
            onRetime = onRetime,
            modifier = paneModifier,
        )
    }
    val list: @Composable (Modifier) -> Unit = { paneModifier ->
        ChapterListPane(
            chapters = chapters,
            selectedChapterId = selectedChapterId,
            playheadMs = playheadMs,
            onSelect = onSelect,
            onNudge = onNudge,
            onSnapToPlayhead = onSnapToPlayhead,
            onToggleLock = onToggleLock,
            onMore = onMore,
            lockedChapterIds = lockedChapterIds,
            query = query,
            onQueryChange = onQueryChange,
            modifier = paneModifier,
        )
    }

    if (isWide) {
        Row(
            modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            timeline(Modifier.weight(1f))
            list(Modifier.width(LIST_PANE_WIDTH))
        }
    } else {
        Column(
            modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            timeline(Modifier.fillMaxWidth())
            list(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun TimelinePane(
    chapters: List<NumberedChapter>,
    bookDurationMs: Long,
    geometry: TimelineGeometry,
    selectedChapterId: String?,
    playheadMs: Long?,
    onSeekFraction: (Float) -> Unit,
    fileBoundaries: List<TimelineFileBoundary>,
    ghosts: List<TimelineChapter>,
    lockedChapterIds: Set<String>,
    onRetime: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val localDensity = LocalDensity.current
    // The lane reports its own measured width; the geometry handed in carries none, because only
    // the lane knows how wide it ended up. Gestures need real pixels to map to real time.
    var laneWidthPx by remember { mutableStateOf(0f) }
    var drag by remember { mutableStateOf<ScrubDrag?>(null) }
    var dragStartY by remember { mutableStateOf(0f) }
    val starts = chapters.map { it.chapter.startTime }
    val density = chapterDensity(starts, bookDurationMs, MINIMAP_BUCKETS)
    val markers =
        chapters.map {
            TimelineChapter(
                id = it.chapter.id,
                number = it.number,
                startMs = it.chapter.startTime,
                locked = it.chapter.id in lockedChapterIds,
                selected = it.chapter.id == selectedChapterId,
            )
        }

    Column(
        modifier
            .clip(PANE_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneLabel(
            leading = stringResource(Res.string.chapter_editor_whole_book),
            trailing = ChapterTimeFormat.clock(bookDurationMs),
        )
        ChapterMiniMap(
            density = density,
            viewportStartFraction = fractionOf(geometry.windowStartMs, bookDurationMs),
            viewportEndFraction = fractionOf(geometry.windowEndMs, bookDurationMs),
            onSeekFraction = onSeekFraction,
            contentDescription = stringResource(Res.string.chapter_editor_minimap_description),
        )
        PaneLabel(
            leading = stringResource(Res.string.chapter_editor_detail_lane),
            trailing = stringResource(Res.string.chapter_editor_zoom_hint),
            modifier = Modifier.padding(top = 10.dp),
        )
        Box {
            ChapterDetailLane(
                geometry = geometry,
                chapters = markers,
                fileBoundaries = fileBoundaries,
                ghosts = ghosts,
                playheadMs = playheadMs,
                contentDescription =
                    stringResource(Res.string.chapter_editor_lane_description, chapters.size),
                modifier =
                    Modifier
                        .onSizeChanged { laneWidthPx = it.width.toFloat() }
                        .pointerInput(markers, laneWidthPx, geometry.windowStartMs, geometry.windowEndMs) {
                            // The lane draws; it does not gesture. Dragging belongs here, with the
                            // caller that owns the draft and the undo stack — which is what keeps
                            // the lane reusable for the drift preview, where nothing is draggable.
                            val lane = geometry.copy(widthPx = laneWidthPx)
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragStartY = offset.y
                                    drag =
                                        chapterGrabbedAt(offset.x, markers, lane)
                                            // A locked boundary is pinned against drift; it must
                                            // not be draggable either, or the lock means two
                                            // different things depending on how you move it.
                                            ?.takeIf { it.id !in lockedChapterIds }
                                            ?.let { ScrubDrag(it.id, it.startMs) }
                                },
                                onDragEnd = { drag = null },
                                onDragCancel = { drag = null },
                            ) { change, amount ->
                                val current = drag ?: return@detectDragGestures
                                // Measured from where the drag began, not from the lane's middle:
                                // a marker grabbed near the top can only be pulled downward, and
                                // anchoring to the centre would make the gesture unreachable there.
                                val pulledDp =
                                    with(localDensity) { (change.position.y - dragStartY).toDp().value }
                                val next =
                                    current.advanced(
                                        dragPx = amount.x,
                                        verticalDistanceDp = pulledDp,
                                        msPerPixel = lane.msPerPixel,
                                    )
                                drag = next
                                onRetime(next.chapterId, next.targetMs)
                            }
                        },
            )
            // Says which step the pull landed in. "Somewhere around a seventh" is not a thing
            // anyone can aim for, which is why the steps are discrete and named.
            drag?.let { active ->
                Text(
                    active.step.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ChapterListPane(
    chapters: List<NumberedChapter>,
    selectedChapterId: String?,
    playheadMs: Long?,
    onSelect: (String) -> Unit,
    onNudge: (String, Long) -> Unit,
    onSnapToPlayhead: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onMore: (String) -> Unit,
    lockedChapterIds: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Filtered here, after numbering: `chapters` arrives numbered against the whole book, so a
    // narrowed list still calls chapter 213 by its real number. See [matching].
    val visible = chapters.matching(query)

    Column(
        modifier
            .clip(PANE_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ListenUpTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(Res.string.chapter_editor_jump_to_title),
            leadingIcon = Icons.Default.Search,
            trailingIcon = if (query.isEmpty()) null else Icons.Default.Close,
            onTrailingClick = { onQueryChange("") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (visible.isEmpty()) {
            Text(
                stringResource(Res.string.chapter_editor_no_matches, query.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Keyed by chapter id, not index: retiming re-sorts the list, and an index key would
            // make Compose reuse the wrong row's state — the selected row's highlight would jump
            // to whatever now occupies its old position.
            items(visible, key = { it.chapter.id }) { numbered ->
                ChapterEditRow(
                    chapter = numbered.chapter,
                    number = numbered.number,
                    isSelected = numbered.chapter.id == selectedChapterId,
                    isPlaying = playheadMs != null && playheadMs.isInside(numbered.chapter),
                    onSelect = { onSelect(numbered.chapter.id) },
                    onNudge = { step -> onNudge(numbered.chapter.id, step) },
                    onSnapToPlayhead = { onSnapToPlayhead(numbered.chapter.id) },
                    onToggleLock = { onToggleLock(numbered.chapter.id) },
                    onMore = { onMore(numbered.chapter.id) },
                    isLocked = numbered.chapter.id in lockedChapterIds,
                )
            }
        }
    }
}

@Composable
private fun PaneLabel(
    leading: String,
    trailing: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = leading,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** Where [ms] sits in the book, as a `0f..1f` fraction. Zero-safe for a book with no duration. */
private fun fractionOf(
    ms: Long,
    bookDurationMs: Long,
): Float = if (bookDurationMs <= 0L) 0f else (ms.toDouble() / bookDurationMs).toFloat().coerceIn(0f, 1f)

/** True when the playhead is inside [chapter]'s span — start inclusive, end exclusive. */
private fun Long.isInside(chapter: Chapter): Boolean =
    this >= chapter.startTime && this < chapter.startTime + chapter.duration
