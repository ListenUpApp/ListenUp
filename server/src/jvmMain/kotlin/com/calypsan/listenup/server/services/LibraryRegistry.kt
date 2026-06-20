package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.SYSTEM_OWNER_ID
import com.calypsan.listenup.server.api.SYSTEM_TYPE_ALL_BOOKS
import com.calypsan.listenup.server.api.SYSTEM_TYPE_INBOX
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock

/**
 * Resolves THE single library for this server process.
 *
 * Finds the one non-deleted library row on first [currentLibrary] call and caches
 * the result for the process lifetime. The library is a singleton: it always exists.
 * If no row is present (fresh install before onboarding), a path-less row named
 * "Library" is created automatically. Folders are added separately — by
 * [Application.bootstrapLibraries] from env paths, or by the user via onboarding.
 *
 * @param sql SQLDelight database the `libraries` row lives in.
 * @param metadataPrecedence the operator-configured textual-metadata precedence.
 */
class LibraryRegistry(
    private val sql: ListenUpDatabase,
    private val metadataPrecedence: MetadataPrecedence = MetadataPrecedence.DEFAULT,
    private val clock: Clock = Clock.System,
) {
    // Holds the raw id string, not LibraryId — AtomicReference uses identity
    // equality, and the @JvmInline value class has no consistent identity.
    private val cachedId = AtomicReference<String?>(null)

    /**
     * The library id for this process. Finds the first non-deleted library
     * on first call, bootstraps one if none exist, then caches for the
     * process lifetime.
     */
    suspend fun currentLibrary(): LibraryId {
        cachedId.get()?.let { return LibraryId(it) }

        val id =
            suspendTransaction(sql) {
                sql.librariesQueries.selectFirstLiveId().executeAsOneOrNull()
                    ?: bootstrapLibrary()
            }

        cachedId.compareAndSet(null, id)
        return LibraryId(cachedId.get()!!)
    }

    private fun bootstrapLibrary(): String {
        // The library is a singleton: it always exists. Folders are added separately
        // (by Application.bootstrapLibraries from env paths, or by the user via onboarding).
        // Uses nextRevision() so the row lands at revision ≥ 1, which makes it visible to
        // pullSince(cursor = 0L) (strictly-greater predicate) across all callers.
        val newId = UUID.randomUUID().toString()
        val now = clock.now().toEpochMilliseconds()
        val serializedPrecedence = metadataPrecedence.serialize()
        val rev = nextRevision()
        sql.librariesQueries.insert(
            id = newId,
            name = "Library",
            metadata_precedence = serializedPrecedence,
            access_mode = "shared",
            created_by_user_id = null,
            created_at = now,
            revision = rev,
            updated_at = now,
            deleted_at = null,
            client_op_id = null,
        )

        // Eagerly create the two per-library system collections in the same bootstrap transaction.
        // Both are owned by the SYSTEM_OWNER_ID sentinel (not a real user) so that bootstrap can
        // run before any admin has registered. Each collection gets its own revision bump so that
        // both rows appear in pullSince(cursor = 0L).
        sql.collectionsQueries.insert(
            id = UUID.randomUUID().toString(),
            library_id = newId,
            owner_id = SYSTEM_OWNER_ID,
            name = "All Books",
            type = SYSTEM_TYPE_ALL_BOOKS,
            created_at = now,
            updated_at = now,
            revision = nextRevision(),
            deleted_at = null,
            client_op_id = null,
        )
        sql.collectionsQueries.insert(
            id = UUID.randomUUID().toString(),
            library_id = newId,
            owner_id = SYSTEM_OWNER_ID,
            name = "Inbox",
            type = SYSTEM_TYPE_INBOX,
            created_at = now,
            updated_at = now,
            revision = nextRevision(),
            deleted_at = null,
            client_op_id = null,
        )

        return newId
    }

    private fun nextRevision(): Long {
        sql.substrateQueries.bumpRevision()
        return sql.substrateQueries.readRevision().executeAsOne()
    }
}
