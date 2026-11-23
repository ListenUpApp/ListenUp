package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.sync.AccessScope
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository

/**
 * The largest scoped [AccessChanged][SyncControl.AccessChanged] delta the server will emit. Above
 * it — too many affected collections or books — the frame degrades to coarse (scope = null) and the
 * client re-derives its whole accessible library, which is cheaper than a huge targeted fetch. 200
 * comfortably covers every realistic single collection mutation while capping the pathological case.
 */
internal const val DELTA_MAX_BOOKS = 200

/**
 * Builds the recipient-agnostic [AccessScope] naming the entities an access change touched, or
 * `null` when the change is too large to delta ([maxBooks]) — the explicit, never-silent fallback to
 * a coarse re-derive. A pure function of the affected ids, so the emission sites can build the scope
 * without any per-user computation and a unit test can pin the threshold directly.
 */
internal fun accessScopeFor(
    collectionIds: Collection<String>,
    bookIds: Collection<String>,
    maxBooks: Int = DELTA_MAX_BOOKS,
): AccessScope? {
    val cols = collectionIds.toSet()
    val books = bookIds.toSet()
    if (cols.size > maxBooks || books.size > maxBooks) return null
    return AccessScope(collectionIds = cols.toList(), bookIds = books.toList())
}

/**
 * Resolves collection-change audiences (owner + active grant recipients) and emits scoped
 * [SyncControl.AccessChanged] nudges to affected users.
 */
internal class CollectionAccessNotifier(
    private val collectionRepo: CollectionRepository,
    private val grantRepo: CollectionGrantRepository,
    private val bus: ChangeBus,
) {
    /**
     * Resolves every real user to nudge for an access change on [collectionIds]: each collection's
     * owner + its active grant recipients, minus the [SYSTEM_OWNER_ID] sentinel (not a real client —
     * ALL_BOOKS reaches its real audience through the per-member default grants enumerated here).
     * Captured separately from [publish] so a cascade (e.g. `deleteCollection`) can resolve the
     * audience *before* tombstoning the grants, then publish *after* the writes commit.
     */
    suspend fun audienceFor(collectionIds: Collection<String>): Set<String> {
        val unique = collectionIds.distinct()
        if (unique.isEmpty()) return emptySet()
        val owners = collectionRepo.findByIds(unique).values.map { it.ownerId }
        val shareUsers = grantRepo.listActiveGrantsForCollections(unique).map { it.sharedWithUserId }
        return (owners + shareUsers).toSet() - SYSTEM_OWNER_ID
    }

    /** Publishes [SyncControl.AccessChanged] carrying [scope] to every user in [userIds]. */
    fun publish(
        userIds: Set<String>,
        scope: AccessScope?,
    ) {
        for (userId in userIds) {
            bus.publishControl(SyncControl.AccessChanged(scope), userId)
        }
    }

    /**
     * Nudges the audience of an access change on [collectionIds] with the [scoped delta][accessScopeFor]
     * of [affectedBookIds] (pass `emptyList()` deliberately when the touched books cannot be enumerated).
     */
    suspend fun notify(
        collectionIds: Collection<String>,
        affectedBookIds: Collection<String>,
    ) {
        val audience = audienceFor(collectionIds)
        if (audience.isEmpty()) return
        publish(audience, accessScopeFor(collectionIds, affectedBookIds))
    }
}
