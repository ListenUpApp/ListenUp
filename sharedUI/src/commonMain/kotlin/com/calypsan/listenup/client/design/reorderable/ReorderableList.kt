package com.calypsan.listenup.client.design.reorderable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import kotlin.math.abs

/**
 * A drag-to-reorder / drag-to-reparent list. Renders [nodes] in stable list order (siblings keep
 * the relative order they appear in [nodes]; a node's subtree stays visually grouped under it),
 * delegating every drop legality/index decision to [ReorderNegotiator]. Long-press an item to pick
 * it up; drag to reposition; release to commit. Visual nesting/indentation, if any, is the
 * caller's responsibility inside [itemContent] — the primitive carries no domain knowledge of
 * what a "depth" means (a flat Reading-Orders list has none; a chapter outline does).
 *
 * @param nodes The current node set. Order matters — see class doc above.
 * @param onMove Invoked once per completed, legal, non-no-op drop with the resolved [ReorderMove].
 * @param itemContent Renders a single node's row content given its id. The primitive wraps this
 *   in the drag-gesture surface and lifted-state visuals — content itself carries no drag logic.
 * @param modifier Applied to the root [Column] that lays the rows out top-to-bottom.
 */
@Composable
fun ReorderableList(
    nodes: List<ReorderNode>,
    onMove: (ReorderMove) -> Unit,
    itemContent: @Composable (nodeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val orderedIds = remember(nodes) { flattenPreOrder(nodes) }
    val dragState = remember(nodes) { ReorderDragState(nodes) }

    Column(modifier = modifier.fillMaxWidth()) {
        orderedIds.forEach { nodeId ->
            ReorderableItemRow(
                nodeId = nodeId,
                nodes = nodes,
                isDragged = nodeId == dragState.draggedId,
                dragOffsetY = dragState.dragOffsetY,
                onSlotPositioned = { centerY -> dragState.recordSlotCenter(nodeId, centerY) },
                onDragStart = {
                    dragState.startDrag(nodeId)
                    haptics.thresholdActivate()
                },
                onDrag = { delta ->
                    if (dragState.updateDrag(nodeId, delta)) haptics.selectionTick()
                },
                onDragEnd = {
                    val move = dragState.endDrag()
                    if (move != null) {
                        haptics.commit()
                        onMove(move)
                    }
                },
                onDragCancel = { dragState.cancelDrag() },
                itemContent = itemContent,
            )
        }
    }
}

/**
 * A single row in a [ReorderableList]: positions itself for hit-testing, applies lifted-state
 * visuals when dragged, and wires the long-press-then-drag gesture to the caller-supplied
 * callbacks. Carries no reorder decision logic itself — that's [ReorderDragState] and
 * [ReorderNegotiator]'s job.
 */
@Composable
private fun ReorderableItemRow(
    nodeId: String,
    nodes: List<ReorderNode>,
    isDragged: Boolean,
    dragOffsetY: Float,
    onSlotPositioned: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    itemContent: @Composable (nodeId: String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .onGloballyPositioned { coords ->
                    onSlotPositioned(coords.positionInParent().y + coords.size.height / 2f)
                }.reorderLiftedVisuals(isDragged, dragOffsetY)
                .pointerInput(nodeId, nodes) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta ->
                            change.consume()
                            onDrag(delta)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                },
    ) {
        itemContent(nodeId)
    }
}

/** Elevated border/shadow/background treatment applied to the item currently being dragged. */
@Composable
private fun Modifier.reorderLiftedVisuals(
    isDragged: Boolean,
    dragOffsetY: Float,
): Modifier =
    if (isDragged) {
        this
            .graphicsLayer { translationY = dragOffsetY }
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
    } else {
        this
    }

/**
 * Mutable drag-in-progress state for a [ReorderableList], scoped to one [nodes] snapshot.
 * Tracks the picked-up node, its running drag offset, each slot's measured center Y, and the
 * currently hovered drop target — then resolves a completed drag through [ReorderNegotiator].
 */
private class ReorderDragState(
    private val nodes: List<ReorderNode>,
) {
    var draggedId: String? by mutableStateOf(null)
        private set
    var dragOffsetY: Float by mutableFloatStateOf(0f)
        private set
    private val slotCenterYs = mutableStateMapOf<String, Float>()
    private var hoverParentId: String? = null
    private var hoverIndex: Int? = null

    /** Records the measured center Y of [nodeId]'s row, used for nearest-slot hit-testing. */
    fun recordSlotCenter(
        nodeId: String,
        centerY: Float,
    ) {
        slotCenterYs[nodeId] = centerY
    }

    /** Picks up [nodeId]: its current parent/index become the initial hover target. */
    fun startDrag(nodeId: String) {
        draggedId = nodeId
        dragOffsetY = 0f
        hoverParentId = nodes.find { it.id == nodeId }?.parentId
        hoverIndex = nodes.filter { it.parentId == hoverParentId }.indexOfFirst { it.id == nodeId }
    }

    /** Advances the drag by [delta]; returns true when the hovered drop slot changed. */
    fun updateDrag(
        nodeId: String,
        delta: Offset,
    ): Boolean {
        dragOffsetY += delta.y
        val pointerY = (slotCenterYs[nodeId] ?: 0f) + dragOffsetY
        val (targetParentId, targetIndex) = nearestSlot(pointerY, nodeId)
        val crossedSlot = targetParentId != hoverParentId || targetIndex != hoverIndex
        if (crossedSlot) {
            hoverParentId = targetParentId
            hoverIndex = targetIndex
        }
        return crossedSlot
    }

    /** Resolves the drag against [ReorderNegotiator] and resets state. Null when illegal/no-op. */
    fun endDrag(): ReorderMove? {
        val id = draggedId
        val move =
            id?.let {
                ReorderNegotiator.resolveMove(
                    nodes,
                    draggedId = it,
                    newParentId = hoverParentId,
                    targetIndex =
                        hoverIndex ?: 0,
                )
            }
        reset()
        return move
    }

    /** Abandons the drag without resolving a move. */
    fun cancelDrag() = reset()

    private fun reset() {
        draggedId = null
        dragOffsetY = 0f
    }

    /** Nearest slot to [pointerY] by measured center Y — same technique as AlphabetScrollbar. */
    private fun nearestSlot(
        pointerY: Float,
        draggedNodeId: String,
    ): Pair<String?, Int> {
        val dragged = nodes.find { it.id == draggedNodeId } ?: return null to 0
        val nearestId = slotCenterYs.minByOrNull { (_, centerY) -> abs(pointerY - centerY) }?.key
        val nearestNode = nodes.find { it.id == nearestId }
        val targetParentId = nearestNode?.parentId ?: dragged.parentId
        val siblingsInOrder = nodes.filter { it.parentId == targetParentId }
        val targetIndex = siblingsInOrder.indexOfFirst { it.id == nearestId }.let { if (it < 0) 0 else it }
        return targetParentId to targetIndex
    }
}

/**
 * Flattens [nodes] into a pre-order (parent-before-children, subtree-contiguous) render sequence,
 * preserving the relative order siblings already have within [nodes].
 */
private fun flattenPreOrder(nodes: List<ReorderNode>): List<String> {
    val childrenByParent = nodes.groupBy { it.parentId }
    val result = mutableListOf<String>()

    fun visit(parentId: String?) {
        childrenByParent[parentId].orEmpty().forEach { node ->
            result += node.id
            visit(node.id)
        }
    }
    visit(null)
    return result
}
