package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
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
 * A moving book with exactly ONE audio file also has that file renamed to match its new folder
 * segment ([AudioFileRename]) — half a job otherwise, and visible to anyone browsing the
 * filesystem. Multi-file books' filenames are left alone.
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
                val files = filesToMove(fromDir, toDir)
                val rename = audioRenameFor(payloadsById[book.id], fromDir, candidate, files)
                entries +=
                    MovePlanEntry(
                        bookId = book.id,
                        fromDir = fromDir,
                        toDir = toDir,
                        toRootRelPath = candidate,
                        files = applyRename(files, fromDir, toDir, rename),
                        collisionResolved = collisionResolved,
                        audioRename = rename,
                    )
            }
            MovePlan(entries)
        }

    /**
     * True when [payload]'s stored path is already what the rules would produce for it.
     *
     * This is how "conformance is maintained, never imposed" is decided. A book that already sits
     * where the rules say is under the organizer's care, so a later metadata edit may move it to
     * keep it there. A book that does not is somewhere its owner put it, and is left alone —
     * consulting the *pre-edit* payload matters, since after an edit every book looks non-canonical.
     */
    fun isCanonical(
        payload: BookSyncPayload,
        settings: OrganizerSettings,
    ): Boolean = OrganizerPathPlanner.planFor(payload.toOrganizeFacts(), settings) == payload.rootRelPath

    /**
     * Plans a single book's relocation — the metadata-edit hook's replan. Returns null when the
     * book is missing/tombstoned or already at its canonical path. Collisions resolve against the
     * DB's natural-key index (another live book already at the target path gets the mover a
     * deterministic ` (n)` suffix), mirroring [build]'s in-memory occupied-set logic.
     */
    suspend fun buildForBook(
        bookId: BookId,
        settings: OrganizerSettings,
    ): MovePlanEntry? =
        suspendTransaction(sql) {
            val book = sql.booksQueries.selectById(bookId.value).executeAsOneOrNull() ?: return@suspendTransaction null
            if (book.deleted_at != null) return@suspendTransaction null
            val payload =
                sql.readBookPayloads(listOf(bookId.value)).firstOrNull() ?: return@suspendTransaction null
            val folderRoot =
                sql.libraryFoldersQueries
                    .selectById(book.folder_id)
                    .executeAsOneOrNull()
                    ?.root_path ?: return@suspendTransaction null

            val planned = OrganizerPathPlanner.planFor(payload.toOrganizeFacts(), settings)
            if (planned == book.root_rel_path) return@suspendTransaction null

            var candidate = planned
            var collisionResolved = false
            var suffix = 2
            while (occupiedByOtherBook(book.folder_id, candidate, bookId.value)) {
                collisionResolved = true
                candidate = withCollisionSuffix(planned, suffix++)
            }

            val fromDir = Path(folderRoot, book.root_rel_path)
            val toDir = Path(folderRoot, candidate)
            val files = filesToMove(fromDir, toDir)
            val rename = audioRenameFor(payload, fromDir, candidate, files)
            MovePlanEntry(
                bookId = bookId.value,
                fromDir = fromDir,
                toDir = toDir,
                toRootRelPath = candidate,
                files = applyRename(files, fromDir, toDir, rename),
                collisionResolved = collisionResolved,
                audioRename = rename,
            )
        }

    /** True when a DIFFERENT live book already occupies `(folderId, relPath)` as its natural key. */
    private fun occupiedByOtherBook(
        folderId: String,
        relPath: String,
        selfBookId: String,
    ): Boolean =
        sql.booksQueries
            .selectIdByNaturalKey(folder_id = folderId, root_rel_path = relPath)
            .executeAsOneOrNull()
            ?.let { it != selfBookId } == true

    /**
     * Every file found under [fromDir] (recursive), paired with its mirrored destination under
     * [toDir]. The `?: file.name` is the same defensive spelling the scanner and backup archiver
     * use: every path here came out of a walk of [fromDir], so the relative form is always
     * present — and flattening to the leaf name is the harmless reading if that ever stops holding.
     */
    private fun filesToMove(
        fromDir: Path,
        toDir: Path,
    ): List<FileMove> =
        listFilesRecursively(fromDir).map { file ->
            FileMove(from = file, to = Path(toDir, file.relativeTo(fromDir) ?: file.name))
        }

    private fun listFilesRecursively(dir: Path): List<Path> {
        if (!SystemFileSystem.exists(dir)) return emptyList()
        return SystemFileSystem.list(dir).flatMap { child ->
            val metadata = SystemFileSystem.metadataOrNull(child)
            if (metadata?.isDirectory == true) listFilesRecursively(child) else listOf(child)
        }
    }
}

/**
 * The rename that aligns a single-file book's audio filename with the folder segment it is moving
 * into, or null when there is nothing to do.
 *
 * Null in every case that isn't unambiguously pure gain: a multi-file book (its filenames often
 * encode ordering, and other tools key on them), a filename nested in a sub-folder, a name that
 * already matches, or a book whose stored filename isn't actually among the files being moved —
 * renaming the DB to a name that never landed on disk would be worse than the stale name.
 */
private fun audioRenameFor(
    payload: BookSyncPayload?,
    fromDir: Path,
    toRootRelPath: String,
    files: List<FileMove>,
): AudioFileRename? {
    val current = payload?.audioFiles?.singleOrNull()?.filename ?: return null
    if (current.contains('/')) return null
    if (files.none { it.from == Path(fromDir, current) }) return null
    val extension = current.substringAfterLast('.', missingDelimiterValue = "")
    val leaf = toRootRelPath.substringAfterLast('/')
    val renamed = if (extension.isEmpty()) leaf else "$leaf.$extension"
    return if (renamed == current) null else AudioFileRename(from = current, to = renamed)
}

/** Redirects [rename]'s source file to its new leaf name under [toDir]; every other move is untouched. */
private fun applyRename(
    files: List<FileMove>,
    fromDir: Path,
    toDir: Path,
    rename: AudioFileRename?,
): List<FileMove> {
    if (rename == null) return files
    val source = Path(fromDir, rename.from)
    return files.map { move ->
        if (move.from == source) move.copy(to = Path(toDir, rename.to)) else move
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
        // Already a number on the payload — the planner owns the folder-segment spelling.
        seriesSequence = primarySeries?.sequence,
        isMultiFile = audioFiles.size > 1,
    )
}
