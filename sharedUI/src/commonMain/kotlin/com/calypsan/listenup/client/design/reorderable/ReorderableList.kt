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

    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val slotCenterYs = remember { mutableStateMapOf<String, Float>() }
    var lastHoverParent by remember { mutableStateOf<String?>(null) }
    var lastHoverIndex by remember { mutableStateOf<Int?>(null) }

    fun currentHoverTarget(pointerY: Float): Pair<String?, Int> {
        val dragged = nodes.find { it.id == draggedId } ?: return null to 0
        // Nearest slot by measured center Y — same technique as AlphabetScrollbar.findLetterAtY.
        val nearestId = slotCenterYs.minByOrNull { (_, centerY) -> abs(pointerY - centerY) }?.key
        val nearestNode = nodes.find { it.id == nearestId }
        val targetParent = nearestNode?.parentId ?: dragged.parentId
        val siblingsInOrder = nodes.filter { it.parentId == targetParent }
        val targetIndex = siblingsInOrder.indexOfFirst { it.id == nearestId }.let { if (it < 0) 0 else it }
        return targetParent to targetIndex
    }

    Column(modifier = modifier.fillMaxWidth()) {
        orderedIds.forEach { nodeId ->
            val isDragged = nodeId == draggedId
            Column(
                modifier =
                    Modifier
                        .onGloballyPositioned { coords ->
                            val center = coords.positionInParent().y + coords.size.height / 2f
                            slotCenterYs[nodeId] = center
                        }.let { base ->
                            if (isDragged) {
                                base
                                    .graphicsLayer { translationY = dragOffsetY }
                                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp))
                                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                            } else {
                                base
                            }
                        }.pointerInput(nodeId, nodes) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedId = nodeId
                                    dragOffsetY = 0f
                                    lastHoverParent = nodes.find { it.id == nodeId }?.parentId
                                    lastHoverIndex =
                                        nodes.filter { it.parentId == lastHoverParent }.indexOfFirst { it.id == nodeId }
                                    haptics.thresholdActivate()
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffsetY += delta.y
                                    val pointerY = (slotCenterYs[nodeId] ?: 0f) + dragOffsetY
                                    val (hoverParent, hoverIndex) = currentHoverTarget(pointerY)
                                    if (hoverParent != lastHoverParent || hoverIndex != lastHoverIndex) {
                                        lastHoverParent = hoverParent
                                        lastHoverIndex = hoverIndex
                                        haptics.selectionTick()
                                    }
                                },
                                onDragEnd = {
                                    val id = draggedId
                                    if (id != null) {
                                        val move =
                                            ReorderNegotiator.resolveMove(
                                                nodes = nodes,
                                                draggedId = id,
                                                newParentId = lastHoverParent,
                                                targetIndex = lastHoverIndex ?: 0,
                                            )
                                        if (move != null) {
                                            haptics.commit()
                                            onMove(move)
                                        }
                                    }
                                    draggedId = null
                                    dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    draggedId = null
                                    dragOffsetY = 0f
                                },
                            )
                        },
            ) {
                itemContent(nodeId)
            }
        }
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
