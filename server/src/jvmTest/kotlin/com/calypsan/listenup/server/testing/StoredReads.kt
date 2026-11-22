package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.auth.toColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry

// Reads a test takes straight from the database to check what a write stored. Clients read these
// facts from their local mirror, so the server offers no RPC for them.

/** The live genre [id] as stored, or null when it is missing or tombstoned. */
internal suspend fun ListenUpDatabase.liveGenre(id: GenreId): GenreSyncPayload? =
    GenreRepository(this, ChangeBus(), SyncRegistry()).findById(id.value)?.takeIf { it.deletedAt == null }

/** The books currently in collection [id] — its live membership rows. */
internal suspend fun SqlTestDatabases.collectionBookIds(id: CollectionId): List<BookId> =
    CollectionBookRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
        .findBookIdsForCollection(id.value)
        .map(::BookId)

/** The books held in [libraryId]'s inbox, or none when the library has no inbox yet. */
internal suspend fun SqlTestDatabases.inboxBookIds(libraryId: String): List<BookId> {
    val inbox =
        CollectionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
            .findInboxForLibrary(libraryId) ?: return emptyList()
    return collectionBookIds(CollectionId(inbox.id))
}

/** What [userId], acting as [role], may do with collection [id]: whether they reach it, how, and whether they own it. */
internal suspend fun SqlTestDatabases.collectionAccess(
    userId: String,
    id: CollectionId,
    role: UserRole = UserRole.MEMBER,
): CollectionAccessPolicy.Decision {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    return CollectionAccessPolicy(
        CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
        CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver),
    ).decide(userId, role.toColumn(), id.value)
}
