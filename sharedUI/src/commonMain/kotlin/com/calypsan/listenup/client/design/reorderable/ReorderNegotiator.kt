package com.calypsan.listenup.client.design.reorderable

/**
 * Pure negotiation core for [ReorderableList]. Given the current [ReorderNode] set and a proposed
 * drop (new parent + target index among that parent's children), computes whether the drop is
 * legal and, if so, the resulting [ReorderMove] — or `null` for an illegal or no-op drop. No
 * Compose, no coroutines, no I/O: a plain function of (nodes, drag) → result, so every rule is a
 * synchronous test.
 */
object ReorderNegotiator {
    /**
     * Resolves a drag of [draggedId] to [targetIndex] among [newParentId]'s children.
     *
     * Returns `null` when:
     * - [draggedId] is not present in [nodes]
     * - [newParentId] is non-null and not present in [nodes]
     * - [newParentId] is non-null and that node's [ReorderNode.canHaveChildren] is `false`
     * - [newParentId] is [draggedId] itself, or a descendant of [draggedId] (would create a
     *   cycle — a subtree can never be dropped inside itself)
     * - the resolved drop is identical to the node's current position (a true no-op)
     *
     * [targetIndex] clamps to `[0, siblingCount]` where `siblingCount` is the new parent's
     * children **excluding** [draggedId] — dragging past either end of the sibling list lands at
     * the nearest legal slot rather than failing.
     */
    fun resolveMove(
        nodes: List<ReorderNode>,
        draggedId: String,
        newParentId: String?,
        targetIndex: Int,
    ): ReorderMove? {
        val dragged = nodes.find { it.id == draggedId } ?: return null
        if (newParentId != null) {
            val parent = nodes.find { it.id == newParentId } ?: return null
            if (!parent.canHaveChildren) return null
            if (isSelfOrDescendant(nodes, ancestorCandidateId = newParentId, subjectId = draggedId)) return null
        }

        val newSiblings = nodes.filter { it.parentId == newParentId && it.id != draggedId }
        val clampedIndex = targetIndex.coerceIn(0, newSiblings.size)

        val currentSiblings = nodes.filter { it.parentId == dragged.parentId && it.id != draggedId }
        val currentIndex = nodes.filter { it.parentId == dragged.parentId }.indexOfFirst { it.id == draggedId }
        // currentIndex counts the dragged node among its siblings; clampedIndex is measured in the
        // siblings-WITHOUT-the-dragged coordinate space. coerceIn maps the former into the latter
        // (a node already last stays last) so the no-op comparison is like-for-like.
        val isNoOp =
            dragged.parentId == newParentId &&
                clampedIndex == currentIndex.coerceIn(0, currentSiblings.size)
        if (isNoOp) return null

        return ReorderMove(movedId = draggedId, newParentId = newParentId, newIndex = clampedIndex)
    }

    /** True if [ancestorCandidateId] is [subjectId] itself or a descendant of it (walks parentId up). */
    private fun isSelfOrDescendant(
        nodes: List<ReorderNode>,
        ancestorCandidateId: String,
        subjectId: String,
    ): Boolean {
        var currentId: String? = ancestorCandidateId
        while (currentId != null) {
            if (currentId == subjectId) return true
            currentId = nodes.find { it.id == currentId }?.parentId
        }
        return false
    }
}
