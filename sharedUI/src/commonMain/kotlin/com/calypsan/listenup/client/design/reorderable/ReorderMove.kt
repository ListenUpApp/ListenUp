package com.calypsan.listenup.client.design.reorderable

/**
 * The single structural result of a completed drag in a [ReorderableList] — covers both a flat
 * reorder (when [newParentId] equals the moved node's original `parentId`) and a reparent (when
 * it differs). The primitive emits this and stops; the caller (a flat adapter mapping to a full
 * `List<Id>` ordering, or a nested adapter mapping to a changed grouping field) decides what it
 * means for its domain.
 *
 * @property movedId The [ReorderNode.id] that was dragged.
 * @property newParentId The dropped-under parent's id, or `null` for root.
 * @property newIndex The moved node's index among its new siblings **after** removal from its
 *   old position — i.e. the index it should occupy in the post-move sibling list.
 */
data class ReorderMove(
    val movedId: String,
    val newParentId: String?,
    val newIndex: Int,
)
