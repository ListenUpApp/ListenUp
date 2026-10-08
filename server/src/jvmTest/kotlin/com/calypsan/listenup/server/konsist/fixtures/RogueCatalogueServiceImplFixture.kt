package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.server.auth.OpenToAllMembers

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
}

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.MutatingRpcsAreGatedRule], so the self-test
 * can prove the rule fires. Lives under `jvmTest`, so production scope never sees it.
 *
 * Exactly three offenders: [deleteThing] (no gate), [clearThing] (its only gate is in a comment), and
 * [mergeThings] (annotated, but with a blank reason). [getThing] is a read, [renameThing] gates and
 * [markThingSeen] carries a reason.
 */
internal class RogueCatalogueServiceImpl : RogueCatalogueService {
    private val touched = mutableListOf<String>()

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

    private fun requireAdmin() = Unit
}
