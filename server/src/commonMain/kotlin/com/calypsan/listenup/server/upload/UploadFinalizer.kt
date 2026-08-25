package com.calypsan.listenup.server.upload

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.dto.uploads.UploadedBook
import com.calypsan.listenup.api.dto.uploads.UploadedBookStatus
import com.calypsan.listenup.api.error.UploadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.failure
import com.calypsan.listenup.api.sync.parseSeriesSequence
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.WriteManifest
import com.calypsan.listenup.server.librarywrite.WriteOp
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.organize.BookOrganizeFacts
import com.calypsan.listenup.server.organize.OrganizerPathPlanner
import com.calypsan.listenup.server.organize.OrganizerSettings
import com.calypsan.listenup.server.organize.OrganizerSettingsStore
import com.calypsan.listenup.server.organize.toPlannerSettings
import com.calypsan.listenup.server.organize.withCollisionSuffix
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.pipeline.Analyzer
import com.calypsan.listenup.server.scanner.pipeline.Grouper
import com.calypsan.listenup.server.scanner.pipeline.Walker
import com.calypsan.listenup.server.scanner.sidecar.DescTxtParser
import com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader
import com.calypsan.listenup.server.scanner.sidecar.NfoParser
import com.calypsan.listenup.server.scanner.sidecar.OpfParser
import com.calypsan.listenup.server.scanner.sidecar.ReaderTxtParser
import com.calypsan.listenup.server.services.LibraryRegistry
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = loggerFor<UploadFinalizer>()

/** Guard on the ` (n)` collision search, so a pathological library can never spin the loop forever. */
private const val MAX_COLLISION_ATTEMPTS = 1_000

/**
 * Turns a staged upload session into library books.
 *
 * The whole design goal is that **nothing here is new**. An uploaded tree is walked, grouped and
 * analysed by the same `Walker → Grouper → Analyzer` the scanner runs, so "which files are one
 * book" is answered identically to a folder someone dropped in by hand — a folder of chapter
 * files is one book, two book-folders are two books, `CD1/`/`CD2/` collapse, a bonus PDF rides
 * along. Its destination comes from [OrganizerPathPlanner] under the admin's live rules, because
 * an arrival always lands structured: we are choosing the path, so there is no "leave it where it
 * is" reading. And it moves through [LibraryWriteBroker], so the write is journaled,
 * watcher-suppressed, containment-checked and crash-resumable exactly like an organizer move.
 *
 * What is genuinely new is only the ordering, and one refusal:
 *
 *  - **Duplicates are refused before anything moves.** A book the library already holds costs one
 *    message naming what it matched; the alternative is a silently doubled library. Replacing an
 *    existing book is a deliberate follow-up, not a side effect of uploading.
 *  - **Per-book independence.** One duplicate or one failed move never denies the other books in
 *    the session their place.
 *
 * **Ingest is triggered, not performed.** Once the files are in the library, the book is created
 * by the ordinary incremental-scan path via [UploadIngestTrigger] — which means covers, inbox
 * gating, sidecar reconciliation and sync events all happen exactly once, in the one place that
 * already knows how. Re-implementing `BookPersister` here to hand back a book id in the response
 * would be a second ingest path to keep in step with the first. The crash window that leaves is
 * benign in a way the organizer's is not: the files are already at their canonical path, so a
 * server that dies before the trigger fires simply has an un-indexed book that the next scan
 * picks up. Nothing is orphaned and nothing is lost.
 */
internal class UploadFinalizer(
    private val staging: UploadStaging,
    private val settingsStore: OrganizerSettingsStore,
    private val duplicates: UploadDuplicateDetector,
    private val broker: LibraryWriteBroker,
    private val libraryRegistry: LibraryRegistry,
    private val sql: ListenUpDatabase,
    private val metadataReader: AbsMetadataReader,
    private val embeddedMetadataParser: EmbeddedMetadataParser,
    private val listenUpSidecarReader: ListenUpSidecarReader,
    private val ingest: UploadIngestTrigger,
) {
    /**
     * Ingests everything staged in [sessionDir] and removes the session.
     *
     * The session directory is swept on **every** exit path, success or failure. A session that
     * has been finalized is finished: leaving its files behind would mean a later attempt could
     * import the same book twice, and the staged copy is worthless the moment the real one is in
     * the library.
     */
    suspend fun finalize(
        sessionId: String,
        sessionDir: Path,
    ): AppResult<UploadFinalizeResult> {
        val swept = staging.sweepPartFiles(sessionDir)
        if (swept > 0) logger.warn { "upload $sessionId: swept $swept incomplete file(s) before ingest" }

        val libraryId = libraryRegistry.currentLibrary()
        val libraryRoot = firstLibraryRoot(libraryId)
        if (libraryRoot == null) {
            staging.deleteSession(sessionDir)
            return failure(UploadError.NoLibraryFolder(debugInfo = "library ${libraryId.value} has no live folder"))
        }

        val analyzed = analyzeStaged(sessionDir)
        if (analyzed.isEmpty()) {
            staging.deleteSession(sessionDir)
            return failure(UploadError.NoBooksFound(debugInfo = "no analyzable book under the session directory"))
        }

        val settings = settingsStore.get().toPlannerSettings()
        val claimedPaths = mutableSetOf<String>()
        val claimedSources = mutableSetOf<String>()
        val results =
            analyzed.mapIndexed { index, book ->
                importOne(
                    sessionId = sessionId,
                    index = index,
                    sessionDir = sessionDir,
                    libraryId = libraryId,
                    libraryRoot = libraryRoot,
                    settings = settings,
                    book = book,
                    claimedPaths = claimedPaths,
                    claimedSources = claimedSources,
                )
            }
        staging.deleteSession(sessionDir)
        return AppResult.Success(UploadFinalizeResult(books = results))
    }

    /** The library folder an upload lands in — the first live one, matching the single-library model. */
    private suspend fun firstLibraryRoot(libraryId: LibraryId): Path? =
        suspendTransaction(sql) {
            sql.libraryFoldersQueries
                .listByLibrary(libraryId.value)
                .executeAsList()
                .firstOrNull()
                ?.root_path
                ?.let(::Path)
        }

    /**
     * `Walker → Grouper → Analyzer` over the staging tree, anchored at [sessionDir].
     *
     * Books that fail analysis are dropped here rather than reported: a candidate the scanner
     * cannot read is not a book we can plan a path for, and the session sweep means nothing is
     * left behind either way. (Surfacing them as inbox items is the design's Stage 3.)
     */
    private suspend fun analyzeStaged(sessionDir: Path): List<AnalyzedBook> {
        val files = Walker().walk(sessionDir).toList()
        val candidates = Grouper().group(files.asFlow()).toList()
        val analyzer =
            Analyzer(
                rootPath = sessionDir,
                metadataReader = metadataReader,
                embeddedMetadataParser = embeddedMetadataParser,
                sidecarParsers = listOf(NfoParser(), OpfParser(), ReaderTxtParser(), DescTxtParser()),
                listenUpSidecarReader = listenUpSidecarReader,
            )
        return analyzer
            .analyze(candidates.asFlow())
            .toList()
            .mapNotNull { result ->
                result.onFailure { logger.warn(it) { "upload: dropping an unanalyzable candidate" } }.getOrNull()
            }
    }

    /** Duplicate-check, plan, and move one analyzed book into the library. */
    private suspend fun importOne(
        sessionId: String,
        index: Int,
        sessionDir: Path,
        libraryId: LibraryId,
        libraryRoot: Path,
        settings: OrganizerSettings,
        book: AnalyzedBook,
        claimedPaths: MutableSet<String>,
        claimedSources: MutableSet<String>,
    ): UploadedBook {
        duplicates.findExisting(libraryId, book)?.let { existing ->
            logger.info {
                "upload $sessionId: refusing '${book.title}' — already in the library as '${existing.title}'"
            }
            return UploadedBook(
                title = book.title,
                status = UploadedBookStatus.DUPLICATE,
                detail = "Your library already has this book as \"${existing.title}\".",
            )
        }

        val canonical = OrganizerPathPlanner.planForArrival(book.toArrivalFacts(), settings)
        val planned = claimFreePath(canonical, libraryRoot, claimedPaths)
        val destDir = Path(libraryRoot, planned)
        val moves = book.plannedMoves(sessionDir, destDir, claimedSources)
        if (moves.isEmpty()) {
            return UploadedBook(
                title = book.title,
                status = UploadedBookStatus.FAILED,
                detail = "That book had no files to import.",
            )
        }

        val manifest = manifestFor(sessionId, index, sessionDir, destDir, moves)
        return when (val outcome = broker.executeManifest(manifest)) {
            is AppResult.Failure -> {
                logger.warn { "upload $sessionId: move failed for '${book.title}': ${outcome.error.debugInfo}" }
                UploadedBook(title = book.title, status = UploadedBookStatus.FAILED, detail = outcome.error.message)
            }

            is AppResult.Success -> {
                // The library now holds the files; the ordinary incremental scan turns them into
                // a book. See the class KDoc on why ingest is triggered rather than performed.
                ingest.reanalyze(destDir)
                UploadedBook(title = book.title, status = UploadedBookStatus.IMPORTED, rootRelPath = planned)
            }
        }
    }

    /**
     * The manifest for one book: the destination directories, then one [WriteOp.ImportFile] per
     * file.
     *
     * `opId` is deterministic per (session, book) rather than freshly minted, so a retry after a
     * partial failure reuses the previous attempt's journal entry — resuming from the first
     * un-done op — instead of orphaning it. Same reasoning as
     * [com.calypsan.listenup.server.organize.MoveManifestExecutor]'s per-book id.
     */
    private fun manifestFor(
        sessionId: String,
        index: Int,
        sessionDir: Path,
        destDir: Path,
        moves: List<Pair<Path, Path>>,
    ): WriteManifest {
        // Distinct parents, shallowest first: ImportFile writes its temp file beside the
        // destination, so every directory in the book's shape has to exist before the first move.
        val dirs = (listOf(destDir) + moves.mapNotNull { it.second.parent }).distinctBy { it.toString() }
        return WriteManifest(
            opId = "upload-import-$sessionId-$index",
            ops =
                buildList {
                    dirs.sortedBy { it.toString().length }.forEach { add(WriteOp.EnsureDir(it)) }
                    moves.forEach { (from, to) -> add(WriteOp.ImportFile(from = from, to = to, fromRoot = sessionDir)) }
                },
        )
    }

    /**
     * [planned], or the first ` (n)` variant of it that neither another book in this batch nor an
     * existing directory on disk has already taken.
     *
     * An arriving book must never be poured into a folder that already holds one. The broker
     * would refuse the individual file moves as ambiguous, but only after the manifest had begun
     * — and a half-merged folder is a far worse outcome than a second copy sitting honestly
     * beside the first under a suffixed name.
     */
    private fun claimFreePath(
        planned: String,
        libraryRoot: Path,
        claimedPaths: MutableSet<String>,
    ): String {
        var candidate = planned
        var suffix = 2

        fun taken(relPath: String) = relPath in claimedPaths || SystemFileSystem.exists(Path(libraryRoot, relPath))
        while (suffix <= MAX_COLLISION_ATTEMPTS && taken(candidate)) {
            candidate = withCollisionSuffix(planned, suffix++)
        }
        claimedPaths += candidate
        return candidate
    }
}

/**
 * Where each of this book's staged files lands under [destDir], as absolute `(from, to)` pairs.
 *
 * The book's shape *below* its root is preserved verbatim — `CD1/`, `extras/`, a `cover.jpg`
 * beside the audio — because that shape is information the uploader supplied and we have no better
 * idea than they did about it. Only the root itself is re-decided, which is the one thing the
 * organizer rules exist to decide.
 *
 * [claimedSources] carries the one case where two books in a session want the *same* staged file.
 * The Grouper hands every loose single-file book at the session root the same set of root-level
 * images, so that two `.m4b`s dropped in beside one `cover.jpg` each keep a cover. That is right
 * for a scan, where the file stays where it is and both books can point at it — but a move has
 * one destination, and the second book's manifest would fail on a source that is no longer there.
 * So a source file belongs to the first book that claims it, and later books simply go without.
 * Audio is never shared, so a book can never lose the thing that makes it a book.
 */
private fun AnalyzedBook.plannedMoves(
    sessionDir: Path,
    destDir: Path,
    claimedSources: MutableSet<String>,
): List<Pair<Path, Path>> =
    candidate.files
        .filter { claimedSources.add(it.relPath) }
        .map { file ->
            val within =
                if (candidate.isFile) {
                    // A loose single-file book: its rootRelPath IS the file, so there is no interior
                    // structure to preserve — every file in the group lands directly in the new folder.
                    file.name
                } else {
                    file.relPath.removePrefix("${candidate.rootRelPath}/")
                }
            file.relPath.resolveUnder(sessionDir) to within.resolveUnder(destDir)
        }

/** [this] slash-separated relative path resolved segment-by-segment under [base]. */
private fun String.resolveUnder(base: Path): Path = split('/').fold(base) { acc, segment -> Path(acc, segment) }

/** Projects an [AnalyzedBook] down to the facts [OrganizerPathPlanner] needs to place an arrival. */
private fun AnalyzedBook.toArrivalFacts(): BookOrganizeFacts {
    val primarySeries = series.firstOrNull()
    return BookOrganizeFacts(
        title = title,
        subtitle = subtitle,
        primaryAuthor = authors.firstOrNull(),
        seriesName = primarySeries?.name,
        seriesSequence = parseSeriesSequence(primarySeries?.sequence),
        isMultiFile = candidate.files.count { it.fileType == FileType.AUDIO } > 1,
    )
}
