package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.features.seriesdetail.components.bookCountLabel
import com.calypsan.listenup.client.features.shelf.shelfReorderActions
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.client.presentation.shelf.reorderedBy
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.series_moved_a11y
import listenup.composeapp.generated.resources.shelf_move_earlier
import listenup.composeapp.generated.resources.shelf_move_later
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private const val LIFTED_SCALE = 1.02f

/**
 * The series' sub-series in sibling order, reorderable three ways: drag by the handle (or a long
 * press anywhere on the row), the visible "Move earlier" / "Move later" buttons, and the same two as
 * TalkBack custom actions. A drag moves the rows live and sends ONE whole new order on drop; each
 * finished move is announced — "Mistborn Era 1 moved to position 2 of 2".
 *
 * The new order is held here until sync brings it back, so the list does not snap to the old order
 * while the server answers; a refusal clears it.
 */
@Composable
internal fun SubSeriesReorderList(
    children: List<SeriesCandidate>,
    enabled: Boolean,
    /** The last change the server refused, if any; a refusal drops the order held for sync. */
    refusal: Any?,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val byId = children.associateBy { it.id.value }
    val ids = children.map { it.id.value }
    var pending by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(ids) { pending = null }
    LaunchedEffect(refusal) { if (refusal != null) pending = null }
    val order = pending?.takeIf { it.toSet() == ids.toSet() } ?: ids

    val drag = remember { SubSeriesDrag() }
    var announcement by remember { mutableStateOf("") }
    val shown = if (drag.draggingId != null) drag.order else order

    var moved by remember { mutableStateOf<Pair<List<String>, String>?>(null) }
    LaunchedEffect(moved) {
        moved?.let { (newOrder, id) ->
            val name = byId[id]?.displayName.orEmpty()
            announcement = getString(Res.string.series_moved_a11y, name, newOrder.indexOf(id) + 1, newOrder.size)
        }
    }

    fun commit(
        newOrder: List<String>,
        movedId: String,
    ) {
        if (newOrder == order) return
        pending = newOrder
        onReorder(newOrder)
        moved = newOrder to movedId
    }

    val dragCallbacks =
        DragCallbacks(
            onStart = { id ->
                if (enabled) {
                    haptics.selectionTick()
                    drag.start(id, order)
                }
            },
            onDrag = { dy -> if (drag.dragBy(dy)) haptics.selectionTick() },
            onEnd = {
                drag.end()?.let { (id, landed) ->
                    haptics.commit()
                    commit(landed, id)
                }
            },
        )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEachIndexed { index, id ->
            byId[id]?.let { child ->
                ReorderableRow(
                    child = child,
                    position = RowPosition(index, shown.lastIndex, order),
                    drag = drag,
                    enabled = enabled,
                    dragCallbacks = dragCallbacks,
                    onCommit = { commit(it, id) },
                )
            }
        }
        Box(
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
        )
    }
}

/** Where a row sits: its index, the last index, and the settled order its moves start from. */
private class RowPosition(
    val index: Int,
    val lastIndex: Int,
    val order: List<String>,
)

/** One row, wired to the drag (lift, offset, reported span) and to its non-drag moves. */
@Composable
private fun ReorderableRow(
    child: SeriesCandidate,
    position: RowPosition,
    drag: SubSeriesDrag,
    enabled: Boolean,
    dragCallbacks: DragCallbacks,
    onCommit: (List<String>) -> Unit,
) {
    val haptics = LocalHaptics.current
    val id = child.id.value
    val index = position.index
    val lifted = id == drag.draggingId
    val earlier = stringResource(Res.string.shelf_move_earlier)
    val later = stringResource(Res.string.shelf_move_later)
    val move = { to: Int ->
        haptics.press()
        onCommit(reorderedBy(position.order, index, to))
    }
    SubSeriesRow(
        child = child,
        lifted = lifted,
        enabled = enabled,
        canMoveEarlier = index > 0,
        canMoveLater = index < position.lastIndex,
        onMoveEarlier = { move(index - 1) },
        onMoveLater = { move(index + 1) },
        dragCallbacks = dragCallbacks,
        modifier =
            Modifier
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer {
                    translationY = if (lifted) drag.offset else 0f
                    scaleX = if (lifted) LIFTED_SCALE else 1f
                    scaleY = if (lifted) LIFTED_SCALE else 1f
                }.onGloballyPositioned { coordinates ->
                    val top = coordinates.positionInParent().y
                    drag.spans[id] = RowSpan(id, top, top + coordinates.size.height)
                }.semantics {
                    if (enabled) {
                        customActions = shelfReorderActions(position.order, index, earlier, later, onCommit)
                    }
                },
    )
}

/** A drag's three moments, for a row's handle and long press alike. */
private class DragCallbacks(
    val onStart: (id: String) -> Unit,
    val onDrag: (dy: Float) -> Unit,
    val onEnd: () -> Unit,
)

@Composable
private fun SubSeriesRow(
    child: SeriesCandidate,
    lifted: Boolean,
    enabled: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    dragCallbacks: DragCallbacks,
    modifier: Modifier = Modifier,
) {
    val id = child.id.value
    val callbacks by rememberUpdatedState(dragCallbacks)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(if (lifted) 6.dp else 0.dp, MaterialTheme.shapes.medium)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .then(
                    if (enabled) {
                        Modifier.pointerInput(id) { dragAfterLongPress(id) { callbacks } }
                    } else {
                        Modifier
                    },
                ).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .then(if (enabled) Modifier.pointerInput(id) { dragNow(id) { callbacks } } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.DragIndicator,
                contentDescription = null,
                tint =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
            )
        }
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(child.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                bookCountLabel(child.bookCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveEarlier, enabled = enabled && canMoveEarlier) {
            Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(Res.string.shelf_move_earlier))
        }
        IconButton(onClick = onMoveLater, enabled = enabled && canMoveLater) {
            Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(Res.string.shelf_move_later))
        }
    }
}

private suspend fun PointerInputScope.dragNow(
    id: String,
    callbacks: () -> DragCallbacks,
) = detectDragGestures(
    onDragStart = { callbacks().onStart(id) },
    onDrag = { change, amount ->
        change.consume()
        callbacks().onDrag(amount.y)
    },
    onDragEnd = { callbacks().onEnd() },
    onDragCancel = { callbacks().onEnd() },
)

private suspend fun PointerInputScope.dragAfterLongPress(
    id: String,
    callbacks: () -> DragCallbacks,
) = detectDragGesturesAfterLongPress(
    onDragStart = { callbacks().onStart(id) },
    onDrag = { change, amount ->
        change.consume()
        callbacks().onDrag(amount.y)
    },
    onDragEnd = { callbacks().onEnd() },
    onDragCancel = { callbacks().onEnd() },
)
