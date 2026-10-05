package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesHierarchy

/** Why a "Move into…" row can't be chosen. */
enum class ParentPickerDisabledReason {
    /** It is already the series' parent — "Current". */
    CURRENT_PARENT,

    /** It is the series being moved — "This series". */
    THIS_SERIES,

    /** It sits inside the series being moved; choosing it would form a loop — "Inside Mistborn". */
    INSIDE_THIS_SERIES,
}

/**
 * One row of the "Move into…" picker. The picker shows the whole tree and greys out what can't be
 * chosen rather than dropping it, so the library's shape never jumps.
 *
 * @property depth 0 for a top-level series; one more per level. Always 0 while searching.
 * @property pathNames the series above this one, root first — shown beside a search result.
 * @property bookCount every book in this series and below it.
 * @property subSeriesCount how many series sit directly inside it.
 * @property isExpanded whether its sub-series are listed below it.
 * @property disabledReason why it can't be chosen; null when it can.
 */
data class ParentPickerRow(
    val id: String,
    val name: String,
    val depth: Int,
    val pathNames: List<String>,
    val bookCount: Int,
    val subSeriesCount: Int,
    val isExpanded: Boolean,
    val disabledReason: ParentPickerDisabledReason?,
) {
    /** Whether the row has sub-series to expand. */
    val hasChildren: Boolean get() = subSeriesCount > 0

    /** Whether choosing this row moves the series. */
    val isSelectable: Boolean get() = disabledReason == null
}

/**
 * Why [candidateId] can't become [currentId]'s parent, or null when it can: the series itself,
 * anything inside it (a loop), or the parent it already has (no change).
 */
internal fun parentDisabledReason(
    hierarchy: SeriesHierarchy,
    currentId: String,
    candidateId: String,
): ParentPickerDisabledReason? =
    when {
        candidateId == currentId -> ParentPickerDisabledReason.THIS_SERIES

        hierarchy.wouldCycle(currentId, candidateId) -> ParentPickerDisabledReason.INSIDE_THIS_SERIES

        hierarchy
            .ancestorsOf(
                currentId,
            ).lastOrNull()
            ?.id
            ?.value == candidateId -> ParentPickerDisabledReason.CURRENT_PARENT

        else -> null
    }

/**
 * The "Move into…" rows for [currentId].
 *
 * Without a [query]: the tree — top-level series by name, each [expanded] one followed by its
 * sub-series in sibling order. With one: every series whose name matches, flat and by name, each
 * carrying its path. Either way the series itself, everything inside it, and its current parent stay
 * listed but disabled, each with its reason.
 */
internal fun parentPickerRows(
    hierarchy: SeriesHierarchy,
    currentId: String,
    query: String,
    expanded: Set<String>,
): List<ParentPickerRow> {
    fun row(
        series: Series,
        depth: Int,
        isExpanded: Boolean,
    ): ParentPickerRow {
        val id = series.id.value
        return ParentPickerRow(
            id = id,
            name = series.name,
            depth = depth,
            pathNames = hierarchy.pathNames(id),
            bookCount = hierarchy.bookCount(id),
            subSeriesCount = hierarchy.childrenOf(id).size,
            isExpanded = isExpanded,
            disabledReason = parentDisabledReason(hierarchy, currentId, id),
        )
    }

    val wanted = query.trim()
    if (wanted.isNotEmpty()) {
        return hierarchy.series
            .filter { it.name.contains(wanted, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { row(it, depth = 0, isExpanded = false) }
    }

    val rows = mutableListOf<ParentPickerRow>()
    val visited = HashSet<String>()

    fun walk(
        series: Series,
        depth: Int,
    ) {
        val id = series.id.value
        if (!visited.add(id)) return
        val open = id in expanded && hierarchy.childrenOf(id).isNotEmpty()
        rows += row(series, depth, open)
        if (open) hierarchy.childrenOf(id).forEach { walk(it, depth + 1) }
    }
    hierarchy.roots.forEach { walk(it, 0) }
    return rows
}

/** The rows the picker opens with expanded: the series' own ancestors, and the series itself. */
internal fun initiallyExpanded(
    hierarchy: SeriesHierarchy,
    currentId: String,
): Set<String> = hierarchy.ancestorsOf(currentId).mapTo(hashSetOf(currentId)) { it.id.value }
