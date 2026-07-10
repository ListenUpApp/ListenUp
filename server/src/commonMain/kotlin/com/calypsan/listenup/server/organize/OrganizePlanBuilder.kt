package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.io.relativeTo
import com.calypsan.listenup.server.services.readBookPayloads
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Builds the full-library [MovePlan] the organizer's save-moment executes: reads every live book
 * in a library, derives its canonical path via [OrganizerPathPlanner], and expands each book
 * whose current path differs into a whole-folder [MovePlanEntry] — a fresh, on-disk enumeration
 * of the book's real files (not just the ones the scanner tracks in the DB), so unmodeled
 * sidecars travel with their book.
 *
 * Already-canonical books are excluded. Collisions between two books' canonical targets (or a
 * moving book's target colliding with a book that's staying put) are resolved deterministically
 * by processing books in `bookId` order and appending a ` (2)`, ` (3)`, … suffix to the losing
 * book's leaf segment.
 */
class OrganizePlanBuilder(
    private val sql: ListenUpDatabase,
) {
    /** Plans a full reorganization of [libraryId] under [settings]. Read-only — no filesystem or DB writes. */
    suspend fun build(
        libraryId: LibraryId,
        settings: OrganizerSettings,
    ): MovePlan =
        suspendTransaction(sql) {
            val liveBooks =
                sql.booksQueries
                    .selectLiveIdsAndPathsForLibrary(libraryId.value)
                    .executeAsList()
                    .sortedBy { it.id }
            if (liveBooks.isEmpty()) return@suspendTransaction MovePlan(emptyList())

            val folderRoots =
                sql.libraryFoldersQueries
                    .selectByIds(liveBooks.map { it.folder_id }.distinct())
                    .executeAsList()
                    .associate { it.id to it.root_path }

            val payloadsById = sql.readBookPayloads(liveBooks.map { it.id }).associateBy { it.id }

            // First pass: derive every book's canonical path and seed the occupied-target set with
            // books that are ALREADY there — a mover's target must never collide with a book that
            // isn't moving, not just with another mover.
            val plannedByBookId = HashMap<String, String>(liveBooks.size)
            val occupiedTargets = HashSet<String>(liveBooks.size)
            for (book in liveBooks) {
                val payload = payloadsById[book.id] ?: continue
                val planned = OrganizerPathPlanner.planFor(payload.toOrganizeFacts(), settings)
                plannedByBookId[book.id] = planned
                if (planned == book.root_rel_path) occupiedTargets += book.root_rel_path
            }

            val entries = mutableListOf<MovePlanEntry>()
            for (book in liveBooks) {
                val planned = plannedByBookId[book.id] ?: continue
                if (planned == book.root_rel_path) continue // already canonical — excluded

                val folderRoot = folderRoots[book.folder_id] ?: continue
                var candidate = planned
                var collisionResolved = false
                var suffix = 2
                while (!occupiedTargets.add(candidate)) {
                    collisionResolved = true
                    candidate = withCollisionSuffix(planned, suffix++)
                }

                val fromDir = Path(folderRoot, book.root_rel_path)
                val toDir = Path(folderRoot, candidate)
                entries +=
                    MovePlanEntry(
                        bookId = book.id,
                        fromDir = fromDir,
                        toDir = toDir,
                        toRootRelPath = candidate,
                        files = filesToMove(fromDir, toDir),
                        collisionResolved = collisionResolved,
                    )
            }
            MovePlan(entries)
        }

    /** Every file found under [fromDir] (recursive), paired with its mirrored destination under [toDir]. */
    private fun filesToMove(
        fromDir: Path,
        toDir: Path,
    ): List<FileMove> =
        listFilesRecursively(fromDir).map { file ->
            FileMove(from = file, to = Path(toDir, file.relativeTo(fromDir)))
        }

    private fun listFilesRecursively(dir: Path): List<Path> {
        if (!SystemFileSystem.exists(dir)) return emptyList()
        return SystemFileSystem.list(dir).flatMap { child ->
            val metadata = SystemFileSystem.metadataOrNull(child)
            if (metadata?.isDirectory == true) listFilesRecursively(child) else listOf(child)
        }
    }
}

/** Appends a deterministic ` (n)` disambiguation suffix to [relPath]'s leaf segment. */
private fun withCollisionSuffix(
    relPath: String,
    n: Int,
): String {
    val idx = relPath.lastIndexOf('/')
    val dirPrefix = if (idx >= 0) relPath.substring(0, idx + 1) else ""
    val leaf = if (idx >= 0) relPath.substring(idx + 1) else relPath
    return "$dirPrefix$leaf ($n)"
}

/** Projects a [BookSyncPayload] down to the facts [OrganizerPathPlanner] needs. */
private fun BookSyncPayload.toOrganizeFacts(): BookOrganizeFacts {
    val primaryAuthor = contributors.firstOrNull { it.role == "author" }?.let { it.creditedAs ?: it.name }
    val primarySeries = series.firstOrNull()
    return BookOrganizeFacts(
        title = title,
        subtitle = subtitle,
        primaryAuthor = primaryAuthor,
        seriesName = primarySeries?.name,
        seriesSequence = primarySeries?.sequence,
        isMultiFile = audioFiles.size > 1,
    )
}
