package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.domain.series.SeriesNode
import com.calypsan.listenup.domain.series.SeriesTree

/** Cap on the parent picker's list — the same as the merge picker's. */
internal const val MAX_PARENT_CANDIDATES = MAX_MERGE_CANDIDATES

/**
 * The series [currentId] may be placed under: every live series except itself and its own
 * sub-series (the server would refuse those as a cycle), filtered by [query], by name.
 */
internal fun parentCandidates(
    allSeries: List<SeriesEntity>,
    currentId: String,
    query: String,
): List<SeriesCandidate> {
    val live = allSeries.filter { it.deletedAt == null }
    val excluded =
        SeriesTree(live.map { SeriesNode(it.id.value, it.parentId, it.parentPosition) }).subtreeOf(currentId)
    return live
        .asSequence()
        .filter { it.id.value !in excluded }
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
        .take(MAX_PARENT_CANDIDATES)
        .map { SeriesCandidate(id = it.id, displayName = it.name, bookCount = 0) }
        .toList()
}
