package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import java.util.UUID
import org.jetbrains.exposed.v1.core.SortOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Resolves the single library id for this server process (Books-A bootstrap
 * shim — superseded by [LibraryRepository] + `LibraryAdminServiceImpl` in the
 * Libraries phase).
 *
 * Finds the first non-deleted library row on first [currentLibrary] call and
 * caches the result for the process lifetime. If no library exists, bootstraps
 * one using `LISTENUP_LIBRARY_PATH` as the name (a placeholder until
 * [LibraryAdminServiceImpl.createLibrary] is wired at startup — see Task 18).
 *
 * TODO: remove once the Libraries phase (Task 18) wires bootstrap in
 * Application.kt and the scanner is reshaped to take a [Library] (Task 10).
 *
 * @param db Exposed database the `libraries` row lives in.
 * @param env environment map (injectable for tests; production passes `System.getenv()`).
 * @param metadataPrecedence the operator-configured textual-metadata precedence.
 */
class LibraryRegistry(
    private val db: Database,
    private val env: Map<String, String>,
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

    // TODO: remove when Task 18 (Application.kt bootstrap) lands — LIB-C.
    private fun bootstrapLibrary(): String {
        val libraryPath = env["LISTENUP_LIBRARY_PATH"]
        val newId = UUID.randomUUID().toString()
        val now = clock.now().toEpochMilliseconds()
        val serializedPrecedence = metadataPrecedence.serialize()
        LibraryTable.insert {
            it[LibraryTable.id] = newId
            it[LibraryTable.name] = libraryPath ?: "Default Library"
            it[LibraryTable.metadataPrecedence] = serializedPrecedence
            it[LibraryTable.createdAt] = now
            it[LibraryTable.updatedAt] = now
            it[LibraryTable.revision] = 0L
            it[LibraryTable.deletedAt] = null
        }
        // Also insert a folder row so loadLibraryFromDb finds the path without racing
        // the test seed. The LibraryFolderTable.rootPath unique index prevents duplicates;
        // if the folder already exists, the insert is skipped (no-op via runCatching).
        if (libraryPath != null) {
            runCatching {
                LibraryFolderTable.insert {
                    it[LibraryFolderTable.id] = UUID.randomUUID().toString()
                    it[LibraryFolderTable.libraryId] = newId
                    it[LibraryFolderTable.rootPath] = libraryPath
                    it[LibraryFolderTable.createdAt] = now
                    it[LibraryFolderTable.updatedAt] = now
                    it[LibraryFolderTable.revision] = 0L
                    it[LibraryFolderTable.deletedAt] = null
                }
            }
        }
        return newId
    }
}
