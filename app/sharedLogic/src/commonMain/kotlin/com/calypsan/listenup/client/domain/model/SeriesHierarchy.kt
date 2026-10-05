package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.domain.series.SeriesNode
import com.calypsan.listenup.domain.series.SeriesTree

/**
 * One book's membership of one series, as the hierarchy counts it — ids only.
 */
data class SeriesBookRef(
    val seriesId: String,
    val bookId: String,
)

/**
 * The whole series hierarchy at one moment: every live series, and every book the library shows in
 * each of them. One snapshot answers every "where does this series sit" and "how many books are under
 * it" question — the Library grid, search, Book Detail, and the series pickers all read it, so they
 * can never disagree. The shape comes from [SeriesTree], shared with the server.
 *
 * @property series every live series (tombstones excluded by the caller).
 * @property memberships the visible books of each series — held books are not in it.
 */
class SeriesHierarchy(
    val series: List<Series>,
    val memberships: List<SeriesBookRef>,
) {
    private val byId: Map<String, Series> = series.associateBy { it.id.value }
    private val tree = SeriesTree(series.map { SeriesNode(it.id.value, it.parentId?.value, it.parentPosition) })
    private val bookIdsBySeries: Map<String, Set<String>> =
        memberships.groupBy({ it.seriesId }, { it.bookId }).mapValues { (_, ids) -> ids.toSet() }

    /** The live series [id], or null when there is none. */
    fun byId(id: String): Series? = byId[id]

    /** The series above [id], root first; empty for a root or an unknown id. */
    fun ancestorsOf(id: String): List<Series> = tree.ancestorsOf(id).mapNotNull(byId::get)

    /** The names of the series above [id], root first — "Cosmere", "Mistborn" for Mistborn Era 1. */
    fun pathNames(id: String): List<String> = ancestorsOf(id).map { it.name }

    /** The direct sub-series of [id], in sibling order. */
    fun childrenOf(id: String): List<Series> = tree.childrenOf(id).mapNotNull(byId::get)

    /** [id] and every series below it. */
    fun subtreeOf(id: String): Set<String> = tree.subtreeOf(id)

    /** Whether [id] sits at the top level: it has no live parent. */
    fun isRoot(id: String): Boolean = tree.ancestorsOf(id).isEmpty()

    /** The series at the top level, by name. */
    val roots: List<Series> by lazy { series.filter { isRoot(it.id.value) }.sortedBy { it.name.lowercase() } }

    /** Every distinct book in [id] and below it — a book in two branches counts once. */
    fun subtreeBookIds(id: String): Set<String> =
        subtreeOf(id).flatMapTo(LinkedHashSet()) { bookIdsBySeries[it].orEmpty() }

    /** How many distinct books [id] and its sub-series hold. */
    fun bookCount(id: String): Int = subtreeBookIds(id).size

    /** True when placing [id] under [newParentId] would put it inside its own subtree. */
    fun wouldCycle(
        id: String,
        newParentId: String,
    ): Boolean = tree.wouldCycle(id, newParentId)

    /** The live series named [name] — trimmed, ignoring case — or null. */
    fun findByName(name: String): Series? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        return series.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
    }

    override fun equals(other: Any?): Boolean =
        other is SeriesHierarchy && other.series == series && other.memberships == memberships

    override fun hashCode(): Int = 31 * series.hashCode() + memberships.hashCode()

    companion object {
        /** No series at all. */
        val Empty = SeriesHierarchy(emptyList(), emptyList())
    }
}
