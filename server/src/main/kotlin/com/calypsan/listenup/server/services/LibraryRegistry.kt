package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryFolderTable
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
 * the result for the process lifetime. If no library row exists yet (first boot
 * before [Application.bootstrapLibraries] has run, or a test fixture that hasn't
 * seeded one), bootstraps a row using the `LISTENUP_LIBRARY_PATH` env var as the
 * path — failing loudly when that var is also absent, because there is nothing
 * sensible to fabricate from.
 *
 * @param db Exposed database the `libraries` row lives in.
 * @param env environment map (injectable for tests). Defaults empty — production
 *   bootstrap runs via [Application.bootstrapLibraries] and no longer relies on
 *   the env-var fallback here.
 * @param metadataPrecedence the operator-configured textual-metadata precedence.
 */
class LibraryRegistry(
    private val db: Database,
    private val env: Map<String, String> = emptyMap(),
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
        // The real bootstrap is Application.bootstrapLibraries. Without a usable
        // env path there is nothing to fabricate a library from — fail loud
        // rather than persist a bogus path-less row. This branch is unreachable
        // post-onboarding (callers resolve a book's owning library, and there
        // are no books before a library exists).
        val libraryPath =
            env["LISTENUP_LIBRARY_PATH"]?.takeIf { it.isNotBlank() }
                ?: error(
                    "No library exists and no LISTENUP_LIBRARY_PATH to bootstrap from — " +
                        "libraries are created via Application.bootstrapLibraries.",
                )
        val newId = UUID.randomUUID().toString()
        val now = clock.now().toEpochMilliseconds()
        val serializedPrecedence = metadataPrecedence.serialize()
        LibraryTable.insert {
            it[LibraryTable.id] = newId
            it[LibraryTable.name] = libraryPath
            it[LibraryTable.metadataPrecedence] = serializedPrecedence
            it[LibraryTable.createdAt] = now
            it[LibraryTable.updatedAt] = now
            it[LibraryTable.revision] = 0L
            it[LibraryTable.deletedAt] = null
        }
        // Also insert a folder row so loadLibraryFromDb finds the path without racing
        // the test seed. The LibraryFolderTable.rootPath unique index prevents duplicates;
        // if the folder already exists, the insert is skipped (no-op via runCatching).
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
        return newId
    }
}
