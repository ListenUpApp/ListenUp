package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.services.SeriesRepository

/**
 * The hierarchy writes [SeriesServiceImpl] and [SeriesMergeReceipts] share. Every write is a plain
 * payload upsert through [SeriesRepository], so each bumps its series' revision and publishes
 * `Updated` like any other series edit.
 */
internal class SeriesHierarchyWrites(
    private val seriesRepo: SeriesRepository,
) {
    /** The live payload of [id], or null when the series is missing or tombstoned. */
    suspend fun live(id: SeriesId): SeriesSyncPayload? = seriesRepo.findById(id.value)?.takeIf { it.deletedAt == null }

    /**
     * The id of [series]' parent when that parent is live, else null. A stored parent can name a
     * tombstoned series; nothing may be lifted onto one.
     */
    suspend fun liveParentOf(series: SeriesSyncPayload): String? =
        series.parentId?.takeIf { live(SeriesId(it)) != null }

    /** Writes [parentId] + [position] onto [series]. */
    suspend fun place(
        series: SeriesSyncPayload,
        parentId: String?,
        position: Int?,
    ): AppResult<Unit> =
        when (val written = seriesRepo.upsert(series.copy(parentId = parentId, parentPosition = position))) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(written.error)
        }

    /**
     * Moves every live sub-series of [from] to the parent [destinationFor] names for it (null for
     * a root), appended after that parent's existing sub-series in their current sibling order.
     */
    suspend fun reparentChildren(
        from: SeriesId,
        destinationFor: (childId: String) -> String?,
    ): AppResult<Unit> {
        val tree = seriesRepo.liveTree()
        val nextPosition = HashMap<String, Int>()
        for (childId in tree.childrenOf(from.value)) {
            val child = live(SeriesId(childId)) ?: continue
            val destination = destinationFor(childId)
            val position =
                destination?.let { parent ->
                    val next = nextPosition.getOrPut(parent) { tree.nextChildPosition(parent) }
                    nextPosition[parent] = next + 1
                    next
                }
            when (val placed = place(child, destination, position)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return placed
            }
        }
        return AppResult.Success(Unit)
    }
}
