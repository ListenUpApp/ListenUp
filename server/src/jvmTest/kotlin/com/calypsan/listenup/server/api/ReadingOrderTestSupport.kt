package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository
import com.calypsan.listenup.server.sync.ReadingOrderFollowRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import io.kotest.matchers.types.shouldBeInstanceOf

/** Everything a reading-order service test needs, over one test database and one change bus. */
internal data class ReadingOrderDeps(
    val dbs: SqlTestDatabases,
    val orders: ReadingOrderRepository,
    val members: ReadingOrderBookRepository,
    val follows: ReadingOrderFollowRepository,
    val hierarchy: HierarchyDeps,
) {
    /** The service as [userId] with [role]. */
    fun serviceAs(
        userId: String,
        role: UserRole = UserRole.MEMBER,
    ): ReadingOrderServiceImpl =
        ReadingOrderServiceImpl(
            orders = orders,
            members = members,
            follows = follows,
            seriesRepo = hierarchy.seriesRepo,
            sqlDb = dbs.sql,
            accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
            permissionPolicy = PermissionPolicy(dbs.sql),
            principal = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("s-$userId"), role) },
        )

    /** Live member book ids of [orderId], first to last. */
    suspend fun bookIdsOf(orderId: String): List<String> = members.liveMembers(orderId).map { it.bookId }
}

internal fun makeReadingOrderDeps(dbs: SqlTestDatabases): ReadingOrderDeps {
    val hierarchy = makeHierarchyDeps(dbs)
    val registry = SyncRegistry()
    return ReadingOrderDeps(
        dbs = dbs,
        orders = hierarchy.readingOrders,
        members = ReadingOrderBookRepository(dbs.sql, hierarchy.bus, registry, dbs.driver),
        follows = ReadingOrderFollowRepository(dbs.sql, hierarchy.bus, registry),
        hierarchy = hierarchy,
    )
}

/** Cosmere > Mistborn. */
internal data class CosmereIds(
    val cosmere: SeriesId,
    val mistborn: SeriesId,
    val discworld: SeriesId,
)

/**
 * Cosmere > Mistborn (books `tfe`, `woa`), Cosmere's own book `elantris`, and Discworld (book `cog`)
 * outside it. The caller seeds the library and folder first (`sql.seedTestLibraryAndFolder()`).
 */
internal suspend fun ReadingOrderDeps.seedCosmere(): CosmereIds {
    val cosmere = hierarchy.seriesRepo.resolveOrCreate("Cosmere")
    val mistborn = hierarchy.seriesRepo.resolveOrCreate("Mistborn")
    val discworld = hierarchy.seriesRepo.resolveOrCreate("Discworld")
    hierarchy.place(mistborn, parent = cosmere, position = 0)
    listOf(
        bookInSeries("tfe", mistborn, 1.0),
        bookInSeries("woa", mistborn, 2.0),
        bookInSeries("elantris", cosmere, 1.0),
        bookInSeries("cog", discworld, 1.0),
    ).forEach { hierarchy.bookRepo.upsert(it).shouldBeInstanceOf<AppResult.Success<*>>() }
    return CosmereIds(cosmere, mistborn, discworld)
}
