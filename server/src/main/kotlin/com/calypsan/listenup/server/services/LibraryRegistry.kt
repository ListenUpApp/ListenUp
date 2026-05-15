package com.calypsan.listenup.server.services

import com.calypsan.listenup.client.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Resolves the single library id for this server process.
 *
 * Books-A is single-library: one `libraries` row, keyed off the
 * `LISTENUP_LIBRARY_PATH` environment variable. On first [currentLibrary] call
 * the registry finds-or-bootstraps that row and caches the resulting
 * [LibraryId] for the process lifetime — subsequent calls never touch the DB.
 *
 * Multi-library support is a future concern; when it lands, the wire gains a
 * per-book `libraryId` and this registry becomes a lookup keyed by path.
 *
 * @param db Exposed database the `libraries` row lives in.
 * @param env environment map (injectable for tests; production passes `System.getenv()`).
 */
class LibraryRegistry(
    private val db: Database,
    private val env: Map<String, String>,
) {
    // Holds the raw id string, not LibraryId — AtomicReference uses identity
    // equality, and the @JvmInline value class has no consistent identity.
    private val cachedId = AtomicReference<String?>(null)

    /**
     * The library id for this process. Find-or-bootstrap on first call, cached after.
     *
     * @throws IllegalStateException if `LISTENUP_LIBRARY_PATH` is unset — the server
     *   cannot operate without a configured library root.
     */
    suspend fun currentLibrary(): LibraryId {
        cachedId.get()?.let { return LibraryId(it) }

        val rootPath =
            env["LISTENUP_LIBRARY_PATH"]
                ?: error("LISTENUP_LIBRARY_PATH not set; cannot bootstrap library")

        val id =
            suspendTransaction(db) {
                LibraryTable
                    .selectAll()
                    .where { LibraryTable.rootPath eq rootPath }
                    .firstOrNull()
                    ?.get(LibraryTable.id)
                    ?: bootstrapLibrary(rootPath)
            }

        // compareAndSet guards the *cache* slot against a concurrent first-caller
        // overwriting it — not the DB read-then-bootstrap above. That find-or-insert
        // is not atomic: two concurrent first-callers could both miss the SELECT and
        // both INSERT, with the second hitting `idx_libraries_root_path` and throwing.
        // Benign in practice — `currentLibrary()` is called once at startup wiring on
        // a single thread. If that assumption ever breaks, wrap the resolve in a Mutex.
        cachedId.compareAndSet(null, id)
        return LibraryId(cachedId.get()!!)
    }

    private fun bootstrapLibrary(rootPath: String): String {
        val newId = UUID.randomUUID().toString()
        LibraryTable.insert {
            it[LibraryTable.id] = newId
            it[LibraryTable.name] = "Default"
            it[LibraryTable.rootPath] = rootPath
        }
        return newId
    }
}
