package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.OpenToAllMembers
import com.calypsan.listenup.server.auth.isAdmin

/** The contract half of [RogueCatalogueServiceImpl]. */
internal interface RogueCatalogueService {
    suspend fun getThing(id: String): String

    suspend fun deleteThing(id: String)

    suspend fun renameThing(
        id: String,
        name: String,
    )

    suspend fun markThingSeen(id: String)

    suspend fun clearThing(id: String)

    suspend fun mergeThings(
        source: String,
        target: String,
    )

    suspend fun listenToThing(id: String)

    suspend fun getOrCreateThing(id: String): String

    suspend fun findAndAnnounceThing(id: String): String

    suspend fun archiveThing(id: String)

    suspend fun restoreThing(id: String)
}

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.MutatingRpcsAreGatedRule], so the self-test
 * can prove the rule fires. Lives under `jvmTest`, so production scope never sees it.
 *
 * Exactly seven offenders: [deleteThing] (no gate), [clearThing] (its only gate is in a comment),
 * [mergeThings] (annotated, but with a blank reason), [listenToThing] (`list` is not its first word),
 * [getOrCreateThing] (read-named, but inserts), [findAndAnnounceThing] (read-named, but publishes) and
 * [archiveThing] (it reads `isAdmin()` but denies nothing). [getThing] is a read, [renameThing] gates,
 * [markThingSeen] carries a reason and [restoreThing] denies non-admins.
 */
internal class RogueCatalogueServiceImpl : RogueCatalogueService {
    private val touched = mutableListOf<String>()

    private val role = UserRole.MEMBER

    private val thingQueries = ThingQueries()

    private val bus = ThingBus()

    override suspend fun getThing(id: String): String = id

    override suspend fun deleteThing(id: String) {
        touched += id
    }

    override suspend fun renameThing(
        id: String,
        name: String,
    ) {
        requireAdmin()
        touched += name
    }

    @OpenToAllMembers(reason = "a listener marks only their own seen state")
    override suspend fun markThingSeen(id: String) {
        touched += id
    }

    override suspend fun clearThing(id: String) {
        // requireAdmin() used to be here
        touched += id
    }

    @OpenToAllMembers(reason = " ")
    override suspend fun mergeThings(
        source: String,
        target: String,
    ) {
        touched += source + target
    }

    override suspend fun listenToThing(id: String) {
        touched += id
    }

    override suspend fun getOrCreateThing(id: String): String {
        thingQueries.insertThing(id)
        return id
    }

    override suspend fun findAndAnnounceThing(id: String): String {
        bus.publish(id)
        return id
    }

    override suspend fun archiveThing(id: String) {
        val byAdmin = role.isAdmin()
        touched += "$id:$byAdmin"
    }

    override suspend fun restoreThing(id: String) {
        if (!role.isAdmin()) return
        touched += id
    }

    private fun requireAdmin() = check(role.isAdmin())

    /** Stands in for a generated SQLDelight queries class. */
    class ThingQueries {
        /** Writes one thing. */
        fun insertThing(id: String) = id
    }

    /** Stands in for the change bus. */
    class ThingBus {
        /** Announces one change. */
        fun publish(id: String) = id
    }
}
