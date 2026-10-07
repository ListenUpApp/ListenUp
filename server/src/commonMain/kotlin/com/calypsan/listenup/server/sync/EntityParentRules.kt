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

    /** Null when [child] may sit under [parent] (null = absent or deleted); the refusal otherwise. */
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

            parent == null -> {
                EntityError.InvalidParent(debugInfo = "parent=$parentId missing or deleted")
            }

            child.kind !in NESTING_KINDS || parent.kind != child.kind -> {
                EntityError.InvalidParent(debugInfo = "kind ${child.kind} cannot sit under ${parent.kind}")
            }

            parent.homeSeriesId != child.homeSeriesId || parent.homeBookId != child.homeBookId -> {
                EntityError.InvalidParent(debugInfo = "parent=$parentId has another home")
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
