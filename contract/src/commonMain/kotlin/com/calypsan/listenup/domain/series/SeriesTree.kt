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
 * @property sortKey what orders books that share a sequence, or have none — the book's title,
 *   wherever the caller has one. Defaults to the book id, which is stable but means nothing to a
 *   reader.
 */
data class SeriesMembership(
    val bookId: String,
    val seriesId: String,
    val sequence: Double?,
    val sortKey: String = bookId,
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

    /**
     * One past the highest position [parentId]'s children hold; 0 when none holds one. A series
     * placed there sorts after every positioned sibling. An unpositioned sibling sorts after every
     * position, so nothing can be appended behind one: a caller that finds any must give them
     * positions first.
     */
    fun nextChildPosition(parentId: String): Int =
        childrenOf(parentId).maxOfOrNull { byId[it]?.parentPosition ?: -1 }?.plus(1) ?: 0

    /**
     * The books of [rootId]'s subtree in series order: each sub-series in sibling order, expanded
     * the same way, followed by the series' own books. Each book appears once, where this walk
     * first reaches it — so a book in two branches is listed with the earlier one, however deep
     * its other membership is.
     *
     * Within one series, books order by sequence with unnumbered books last, then by
     * [SeriesMembership.sortKey], then by book id. The result never depends on the order of
     * [memberships], so the client and the server always agree.
     */
    fun defaultBookOrder(
        rootId: String,
        memberships: Collection<SeriesMembership>,
    ): List<String> = defaultBookGroups(rootId, memberships).values.flatten()

    /**
     * [defaultBookOrder], kept in its groups: for every series of [rootId]'s subtree, in the walk's
     * order (each series after its own sub-series), the books that the walk first reaches there.
     * A series with no books of its own — or whose books were all reached earlier — still has a
     * group, so a caller drawing headings sees the subtree's whole shape. Flattening the values
     * gives exactly [defaultBookOrder].
     */
    fun defaultBookGroups(
        rootId: String,
        memberships: Collection<SeriesMembership>,
    ): Map<String, List<String>> {
        val bySeries = memberships.groupBy { it.seriesId }
        val emitted = HashSet<String>()
        val visiting = HashSet<String>()
        val groups = LinkedHashMap<String, List<String>>()

        fun walk(seriesId: String) {
            if (!visiting.add(seriesId)) return
            childrenOf(seriesId).forEach(::walk)
            groups[seriesId] =
                bySeries[seriesId]
                    .orEmpty()
                    .sortedWith(BOOK_ORDER)
                    .map { it.bookId }
                    .filter(emitted::add)
        }
        walk(rootId)
        return groups
    }

    private companion object {
        val BOOK_ORDER: Comparator<SeriesMembership> =
            compareBy<SeriesMembership> { it.sequence == null }
                .thenBy { it.sequence }
                .thenBy { it.sortKey }
                .thenBy { it.bookId }
    }
}
