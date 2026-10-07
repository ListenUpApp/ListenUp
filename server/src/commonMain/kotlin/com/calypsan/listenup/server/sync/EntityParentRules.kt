package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload

/**
 * Pure rules for an entity's parent: only places nest in places and groups in groups, a parent shares
 * its child's home (so it is never more visible than the child), and the chain never loops.
 * [parentOf] reads one stored `parent_id`; the walk is bounded so a corrupt chain can't hang a write.
 */
internal object EntityParentRules {
    private val NESTING_KINDS = setOf(EntityKind.LOCATION, EntityKind.GROUP)
    private const val MAX_DEPTH = 1_000
    private const val UNAVAILABLE_PARENT = "parent is not a live entry in this series or book"

    /**
     * Null when [child] may sit under [parent] (null = absent or deleted); the refusal otherwise. An absent,
     * deleted or other-home parent gets one identical refusal, so it never reveals a hidden entity.
     */
    fun check(
        child: EntitySyncPayload,
        parent: EntitySyncPayload?,
        parentOf: (String) -> String?,
    ): EntityError? {
        val parentId = child.parentId ?: return null
        return when {
            parentId == child.id -> {
                EntityError.CycleDetected(debugInfo = "entity=${child.id} names itself")
            }

            // Missing, deleted and elsewhere are one answer, decided before anything about the parent
            // itself: a parent in another home may be one the caller can't see, and debugInfo crosses the
            // wire — a distinct answer would confirm it exists, and the kind check would name its kind.
            parent == null || parent.homeSeriesId != child.homeSeriesId || parent.homeBookId != child.homeBookId -> {
                EntityError.InvalidParent(debugInfo = UNAVAILABLE_PARENT)
            }

            child.kind !in NESTING_KINDS || parent.kind != child.kind -> {
                EntityError.InvalidParent(debugInfo = "kind ${child.kind} cannot sit under ${parent.kind}")
            }

            isAncestor(ancestorId = child.id, startingAt = parent.parentId, parentOf = parentOf) -> {
                EntityError.CycleDetected(debugInfo = "entity=${child.id} would contain itself")
            }

            else -> {
                null
            }
        }
    }

    /**
     * True when [ancestorId] appears on the chain that starts at [startingAt] and climbs via [parentOf] — or
     * when the chain is still going after [MAX_DEPTH] steps, which only a stored loop can do.
     */
    fun isAncestor(
        ancestorId: String,
        startingAt: String?,
        parentOf: (String) -> String?,
    ): Boolean {
        var cursor = startingAt
        var steps = 0
        while (cursor != null && steps < MAX_DEPTH) {
            if (cursor == ancestorId) return true
            cursor = parentOf(cursor)
            steps++
        }
        // A walk that ran out of steps with the chain still going is a stored loop: refuse it as a cycle.
        return cursor != null
    }
}
