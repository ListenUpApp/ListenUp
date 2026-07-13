package com.calypsan.listenup.client.design.reorderable

/**
 * A single node in the (optionally nested) list a [ReorderableList] renders and reorders.
 * Order among siblings is implied by position in the `nodes: List<ReorderNode>` list passed to
 * [ReorderNegotiator] and [ReorderableList] — there is no separate sort-index field; the caller's
 * list order **is** the sibling order. `depth 0` (top-level) nodes carry `parentId = null`.
 *
 * @property id Stable identifier, unique across the whole node set (not just within a parent).
 * @property parentId The containing node's [id], or `null` for a root-level node.
 * @property canHaveChildren Whether other nodes may be reparented under this one. A flat list
 *   (Reading Orders) sets this `false` on every node — nothing is ever a legal drop target as a
 *   parent, so every move degrades to a plain reorder.
 */
data class ReorderNode(
    val id: String,
    val parentId: String?,
    val canHaveChildren: Boolean,
)
