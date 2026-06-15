package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Resolves THE single library for this server process.
 *
 * Finds the one non-deleted library row on first [currentLibrary] call and caches
 * the result for the process lifetime. The library is a singleton: it always exists.
 * If no row is present (fresh install before onboarding), a path-less row named
 * "Library" is created automatically. Folders are added separately — by
 * [Application.bootstrapLibraries] from env paths, or by the user via onboarding.
 *
 * @param db Exposed database the `libraries` row lives in.
 * @param metadataPrecedence the operator-configured textual-metadata precedence.
 */
class LibraryRegistry(
    private val db: Database,
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
     *
     * Safe to call from inside an already-open Exposed transaction on the same
     * [Database]: the `suspendTransaction` below joins the enclosing transaction.
     */
    suspend fun currentLibrary(): LibraryId {
        cachedId.get()?.let { return LibraryId(it) }

        val id =
            suspendTransaction(db) {
                LibraryTable
                    .selectAll()
                    .where { LibraryTable.deletedAt.isNull() }
                    .orderBy(LibraryTable.createdAt)
                    .firstOrNull()
                    ?.get(LibraryTable.id)
                    ?: bootstrapLibrary()
            }

        cachedId.compareAndSet(null, id)
        return LibraryId(cachedId.get()!!)
    }

    private fun bootstrapLibrary(): String {
        // The library is a singleton: it always exists. Folders are added separately
        // (by Application.bootstrapLibraries from env paths, or by the user via onboarding).
        val newId = UUID.randomUUID().toString()
        val now = clock.now().toEpochMilliseconds()
        val serializedPrecedence = metadataPrecedence.serialize()
        LibraryTable.insert {
            it[LibraryTable.id] = newId
            it[LibraryTable.name] = "Library"
            it[LibraryTable.metadataPrecedence] = serializedPrecedence
            it[LibraryTable.createdAt] = now
            it[LibraryTable.updatedAt] = now
            it[LibraryTable.revision] = 0L
            it[LibraryTable.deletedAt] = null
        }
        return newId
    }
}
