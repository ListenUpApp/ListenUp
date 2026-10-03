package com.calypsan.listenup.domain.series

/**
 * One series as the hierarchy sees it: its id, its parent (null for a root) and its place among
 * its siblings.
 *
 * @property parentPosition order among siblings, lowest first; null sorts after every position.
 */
data class SeriesNode(
    val id: String,
    val parentId: String?,
    val parentPosition: Int?,
)

/**
 * One book's membership of one series.
 *
 * @property sequence the book's position in that series (1.0, 1.5); null when unnumbered.
 */
data class SeriesMembership(
    val bookId: String,
    val seriesId: String,
    val sequence: Double?,
)

/**
 * The series hierarchy, built from the flat list of live series.
 *
 * Shared by the server (cycle checks, re-parenting) and the client (breadcrumbs, sub-series,
 * the default book order of a parent) so both sides answer every tree question identically.
 * A node whose parent is not in the list is a root. Every walk carries a visited set, so corrupt
 * data that contains a cycle can never hang a caller.
 */
class SeriesTree(
    nodes: Collection<SeriesNode>,
) {
    private val byId: Map<String, SeriesNode> = nodes.associateBy { it.id }

    private val childIds: Map<String, List<String>> =
        nodes
            .filter { it.parentId != null && it.parentId in byId && it.parentId != it.id }
            .groupBy { it.parentId!! }
            .mapValues { (_, children) ->
                children
                    .sortedWith(compareBy<SeriesNode> { it.parentPosition ?: Int.MAX_VALUE }.thenBy { it.id })
                    .map { it.id }
            }

    /** The ancestors of [id], root first, excluding [id] itself. Empty for a root or unknown id. */
    fun ancestorsOf(id: String): List<String> {
        val seen = hashSetOf(id)
        val chain = ArrayList<String>()
        var current = byId[id]?.parentId
        while (current != null && current in byId && seen.add(current)) {
            chain += current
            current = byId[current]?.parentId
        }
        return chain.asReversed()
    }

    /** The direct children of [id] in sibling order. */
    fun childrenOf(id: String): List<String> = childIds[id].orEmpty()

    /** [id] and every descendant. */
    fun subtreeOf(id: String): Set<String> {
        val seen = linkedSetOf(id)
        val pending = ArrayDeque(listOf(id))
        while (pending.isNotEmpty()) {
            for (child in childrenOf(pending.removeFirst())) {
                if (seen.add(child)) pending += child
            }
        }
        return seen
    }

    /** True when making [newParentId] the parent of [id] would put [id] inside its own subtree. */
    fun wouldCycle(
        id: String,
        newParentId: String,
    ): Boolean = newParentId in subtreeOf(id)

    /** The position a series appended to [parentId]'s children should take. */
    fun nextChildPosition(parentId: String): Int =
        childrenOf(parentId).maxOfOrNull { byId[it]?.parentPosition ?: -1 }?.plus(1) ?: 0

    /**
     * The books of [rootId]'s subtree in series order: each sub-series in sibling order, expanded
     * the same way, followed by the books that belong to the series directly and to none of its
     * descendants, by sequence with unnumbered books last. Each book appears once.
     */
    fun defaultBookOrder(
        rootId: String,
        memberships: Collection<SeriesMembership>,
    ): List<String> {
        val bySeries = memberships.groupBy { it.seriesId }
        val emitted = LinkedHashSet<String>()
        val visiting = HashSet<String>()

        fun walk(seriesId: String) {
            if (!visiting.add(seriesId)) return
            childrenOf(seriesId).forEach(::walk)
            bySeries[seriesId]
                .orEmpty()
                .sortedBy { it.sequence ?: Double.MAX_VALUE }
                .forEach { emitted += it.bookId }
        }
        walk(rootId)
        return emitted.toList()
    }
}
