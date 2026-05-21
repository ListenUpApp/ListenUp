package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

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
 * @param metadataPrecedence the operator-configured textual-metadata precedence
 *   (resolved from `LISTENUP_METADATA_PRECEDENCE`). The running scanner uses
 *   this env-resolved value, threaded directly to the `Analyzer`. This value
 *   is persisted onto the `libraries` row as forward-storage for the future
 *   per-library Libraries domain phase; it is not read back today.
 */
class LibraryRegistry(
    private val db: Database,
    private val env: Map<String, String>,
    private val metadataPrecedence: MetadataPrecedence = MetadataPrecedence.DEFAULT,
) {
    // Holds the raw id string, not LibraryId — AtomicReference uses identity
    // equality, and the @JvmInline value class has no consistent identity.
    private val cachedId = AtomicReference<String?>(null)

    /**
     * The library id for this process. Find-or-bootstrap on first call, cached after.
     *
     * Safe to call from inside an already-open Exposed transaction on the same
     * [Database]: the `suspendTransaction` below joins the enclosing transaction
     * (Exposed reuses the connection in the coroutine context) rather than opening
     * a second connection — so callers like `BookRepository.writePayload`, which run
     * inside the sync substrate's open transaction, can resolve the library id
     * inline without nesting hazards. The first call does one SELECT (+ optional
     * INSERT); every call after is a pure cache read with no DB access at all.
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
                val existing =
                    LibraryTable
                        .selectAll()
                        .where { LibraryTable.rootPath eq rootPath }
                        .firstOrNull()
                if (existing != null) {
                    // Reconcile: keep the column in sync with the live configured value
                    // so it never silently diverges when an operator changes
                    // LISTENUP_METADATA_PRECEDENCE between restarts.
                    val currentPrecedence = metadataPrecedence.serialize()
                    if (existing[LibraryTable.metadataPrecedence] != currentPrecedence) {
                        LibraryTable.update({ LibraryTable.id eq existing[LibraryTable.id] }) {
                            it[LibraryTable.metadataPrecedence] = currentPrecedence
                        }
                    }
                    existing[LibraryTable.id]
                } else {
                    bootstrapLibrary(rootPath)
                }
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
        val precedence = metadataPrecedence.serialize()
        LibraryTable.insert {
            it[LibraryTable.id] = newId
            it[LibraryTable.name] = "Default"
            it[LibraryTable.rootPath] = rootPath
            it[LibraryTable.metadataPrecedence] = precedence
        }
        return newId
    }
}
