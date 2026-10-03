package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.services.SeriesRepository

/**
 * The series hierarchy writes: the three edits [SeriesServiceImpl] exposes ([create], [setParent],
 * [reorderChildren]) and the re-parenting steps delete and merge share with [SeriesMergeReceipts].
 * Each edit validates against the live [com.calypsan.listenup.domain.series.SeriesTree]; every
 * write is a plain payload upsert through [SeriesRepository], so each bumps its series' revision
 * and publishes `Updated` like any other series edit.
 *
 * Nothing here checks permissions — the service gates the caller before delegating.
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

    /**
     * Hands the sub-series of [source] on, ahead of [source] being merged into [target]: they
     * follow it into the target, after the target's own sub-series. One that IS the target, or
     * contains it, can't sit under the target — it takes the source's own slot in the tree instead.
     */
    suspend fun handChildrenTo(
        source: SeriesSyncPayload,
        target: SeriesId,
    ): AppResult<Unit> {
        val tree = seriesRepo.liveTree()
        val heir =
            tree
                .childrenOf(source.id)
                .firstOrNull { target.value in tree.subtreeOf(it) }
                ?.let { live(SeriesId(it)) }
        if (heir != null) {
            val parent = liveParentOf(source)
            when (val placed = place(heir, parent, source.parentPosition.takeIf { parent != null })) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return placed
            }
        }
        return reparentChildren(SeriesId(source.id)) { target.value }
    }

    /**
     * Creates the series [name] under [parentId] (null for a root), appended after that parent's
     * existing sub-series. Refuses a blank or over-long name, a missing parent, and a name a live
     * series already holds.
     */
    suspend fun create(
        name: String,
        parentId: SeriesId?,
    ): AppResult<SeriesSyncPayload> {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > SeriesUpdate.MAX_NAME) {
            return AppResult.Failure(SeriesError.InvalidInput(debugInfo = "name length=${trimmed.length}"))
        }
        if (parentId != null && live(parentId) == null) return parentNotFound(parentId)
        return when (val claimed = claimName(trimmed)) {
            is AppResult.Success -> placeCreated(claimed.data, parentId)
            is AppResult.Failure -> claimed
        }
    }

    /** Resolves [name] to a series this call may place: a new one, or a revived tombstone. */
    private suspend fun claimName(name: String): AppResult<SeriesId> {
        if (seriesRepo.liveIdForName(name) != null) return nameAlreadyExists(name)
        val id = seriesRepo.resolveOrCreate(name)
        // A name that was merged away resolves to the series it was merged into — a different
        // live series, which this call must not re-parent.
        return if (seriesRepo.liveIdForName(name) != id) nameAlreadyExists(name) else AppResult.Success(id)
    }

    /** Puts the just-claimed series [id] under [parentId] and returns it as stored. */
    private suspend fun placeCreated(
        id: SeriesId,
        parentId: SeriesId?,
    ): AppResult<SeriesSyncPayload> {
        // A revived series comes back holding its old parent and position. Clear them first, so it
        // lands after the requested parent's current sub-series — or stays a root — like a new one.
        val resolved = live(id) ?: return seriesNotFound(id)
        if (resolved.parentId != null) {
            when (val cleared = place(resolved, null, null)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return cleared
            }
        }
        when (val placed = setParent(id, parentId)) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return placed
        }
        val created = live(id) ?: return seriesNotFound(id)
        return AppResult.Success(created)
    }

    /**
     * Moves [id] under [parentId], appended after that parent's sub-series; null makes it a root.
     * Refuses a missing parent and a parent that is the series itself or one of its sub-series.
     */
    suspend fun setParent(
        id: SeriesId,
        parentId: SeriesId?,
    ): AppResult<Unit> {
        val current = live(id) ?: return seriesNotFound(id)
        if (parentId == null) {
            return if (current.parentId == null) AppResult.Success(Unit) else place(current, null, null)
        }
        live(parentId) ?: return parentNotFound(parentId)
        val tree = seriesRepo.liveTree()
        if (tree.wouldCycle(id.value, parentId.value)) {
            return AppResult.Failure(
                SeriesError.HierarchyCycle(debugInfo = "series=${id.value} parent=${parentId.value}"),
            )
        }
        if (current.parentId == parentId.value) return AppResult.Success(Unit)
        return place(current, parentId.value, tree.nextChildPosition(parentId.value))
    }

    /** Rewrites the sibling order of [parentId]'s sub-series to [orderedChildIds], a permutation of them. */
    suspend fun reorderChildren(
        parentId: SeriesId,
        orderedChildIds: List<SeriesId>,
    ): AppResult<Unit> {
        live(parentId) ?: return seriesNotFound(parentId)
        val current = seriesRepo.liveTree().childrenOf(parentId.value)
        val requested = orderedChildIds.map { it.value }
        if (requested.size != current.size || requested.toSet() != current.toSet()) {
            return AppResult.Failure(
                SeriesError.InvalidInput(
                    debugInfo = "reorder of ${parentId.value} is not a permutation of its sub-series",
                ),
            )
        }
        orderedChildIds.forEachIndexed { index, childId ->
            val child = live(childId) ?: return seriesNotFound(childId)
            if (child.parentPosition != index) {
                when (val placed = place(child, parentId.value, index)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> return placed
                }
            }
        }
        return AppResult.Success(Unit)
    }
}

private fun parentNotFound(parentId: SeriesId): AppResult.Failure =
    AppResult.Failure(SeriesError.ParentNotFound(debugInfo = "parent=${parentId.value}"))

private fun nameAlreadyExists(name: String): AppResult.Failure =
    AppResult.Failure(SeriesError.NameAlreadyExists(debugInfo = "name=$name"))
