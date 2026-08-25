package com.calypsan.listenup.server.upload

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.dto.uploads.UploadedBookStatus
import com.calypsan.listenup.api.error.UploadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.SqlLibraryRootProvider
import com.calypsan.listenup.server.librarywrite.WriteJournal
import com.calypsan.listenup.server.librarywrite.tempJournalDir
import com.calypsan.listenup.server.organize.OrganizerSettingsStore
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.pipeline.Analyzer
import com.calypsan.listenup.server.scanner.pipeline.Grouper
import com.calypsan.listenup.server.scanner.pipeline.Walker
import com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.IngestOutcome
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sidecar.SidecarWriteStateRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path as IoPath

/**
 * The upload finalize pipeline end to end, against a real database, a real staging directory and
 * a real library folder: staged tree → scanner grouping → duplicate check → planned path → broker
 * move → session swept.
 *
 * The assertions that matter are the ones about *grouping* and *containment*: a folder of chapter
 * files must arrive as ONE book because the scanner's `Grouper` says so, not because uploads
 * carry a heuristic of their own; and a refused book must leave nothing whatsoever in the library.
 */
class UploadFinalizeE2ETest :
    FunSpec({

        test("a folder of chapter files becomes ONE book at its canonical path, bonus files riding along") {
            withUploadRig { rig ->
                val session = rig.newSession()
                rig.stage(session, "The Way of Kings/01.m4b", "a")
                rig.stage(session, "The Way of Kings/02.m4b", "b")
                rig.stage(session, "The Way of Kings/cover.jpg", "img")
                rig.stage(session, "The Way of Kings/extras/map.pdf", "pdf")
                rig.stageAbsMetadata(
                    session,
                    "The Way of Kings",
                    title = "The Way of Kings",
                    author = "Brandon Sanderson",
                    series = "Stormlight Archive #1",
                )

                val result = rig.finalize(session)

                result.books.size shouldBe 1
                val book = result.books.single()
                book.status shouldBe UploadedBookStatus.IMPORTED
                book.title shouldBe "The Way of Kings"
                val canonical = "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings"
                book.rootRelPath shouldBe canonical

                // Every staged file landed, with its shape below the book root preserved.
                rig.libraryFilesUnder(canonical) shouldContainExactlyInAnyOrder
                    listOf("01.m4b", "02.m4b", "cover.jpg", "metadata.json", "extras/map.pdf")

                // The staging session is gone — nothing left half-uploaded to trip over later.
                rig.sessionExists(session) shouldBe false

                // Ingest was triggered for exactly the destination directory.
                rig.ingested.map { it.toString() } shouldBe listOf(rig.libraryRoot.resolve(canonical).toString())
            }
        }

        test("no orphan: the crash window leaves a complete book on disk that a later scan finds") {
            // The window between the broker reporting success and the book row appearing. The rig's
            // trigger only records — no scan actually runs — which is exactly the state a process
            // that died mid-finalize would leave behind. For an upload that is benign in a way the
            // organizer's equivalent is not: the files are already at their canonical path, so the
            // next scan simply finds them, under the path finalize planned.
            withUploadRig { rig ->
                val session = rig.newSession()
                rig.stage(session, "Elantris/Elantris.m4b", "a")
                rig.stageAbsMetadata(session, "Elantris", title = "Elantris", author = "Brandon Sanderson")

                val canonical = "Brandon Sanderson/Elantris"
                rig
                    .finalize(session)
                    .books
                    .single()
                    .rootRelPath shouldBe canonical
                rig.sessionExists(session) shouldBe false

                val ingested = rig.scanAndIngestLibrary()
                ingested.size shouldBe 1
                rig.sql.booksQueries
                    .selectById(ingested.single())
                    .executeAsOne()
                    .root_rel_path shouldBe canonical
            }
        }

        test("two book folders in one session become TWO books") {
            withUploadRig { rig ->
                val session = rig.newSession()
                rig.stage(session, "Elantris/Elantris.m4b", "a")
                rig.stageAbsMetadata(session, "Elantris", title = "Elantris", author = "Brandon Sanderson")
                rig.stage(session, "Warbreaker/Warbreaker.m4b", "b")
                rig.stageAbsMetadata(session, "Warbreaker", title = "Warbreaker", author = "Brandon Sanderson")

                val result = rig.finalize(session)

                result.books.size shouldBe 2
                result.books.all { it.status == UploadedBookStatus.IMPORTED } shouldBe true
                result.books.map { it.rootRelPath } shouldContainExactlyInAnyOrder
                    listOf("Brandon Sanderson/Elantris", "Brandon Sanderson/Warbreaker")
            }
        }

        test("a duplicate ASIN is refused by name, and leaves NOTHING in the library") {
            withUploadRig { rig ->
                // Seed an existing book carrying the ASIN, through the real ingest path.
                rig.seedLibraryBook(
                    relPath = "existing/wok",
                    title = "The Way of Kings",
                    author = "Brandon Sanderson",
                    asin = "B0041I5NBG",
                )

                val session = rig.newSession()
                rig.stage(session, "wok-rip/01.m4b", "a")
                rig.stageAbsMetadata(
                    session,
                    "wok-rip",
                    title = "The Way of Kings",
                    author = "Brandon Sanderson",
                    asin = "B0041I5NBG",
                )

                val before = rig.allLibraryFiles()
                val result = rig.finalize(session)

                val refused = result.books.single()
                refused.status shouldBe UploadedBookStatus.DUPLICATE
                refused.rootRelPath shouldBe null
                withClue("the refusal must name the book the admin already has") {
                    refused.detail.shouldNotBeNull() shouldContain "The Way of Kings"
                }
                withClue("a refused upload writes nothing: the library must be byte-identical") {
                    rig.allLibraryFiles() shouldBe before
                }
                rig.ingested shouldBe emptyList()
                rig.sessionExists(session) shouldBe false
            }
        }

        test("a duplicate title and author is refused even with no ASIN on either side") {
            withUploadRig { rig ->
                rig.seedLibraryBook(relPath = "existing/elantris", title = "Elantris", author = "Brandon Sanderson")

                val session = rig.newSession()
                rig.stage(session, "elantris-again/01.m4b", "a")
                rig.stageAbsMetadata(session, "elantris-again", title = "Elantris", author = "Brandon Sanderson")

                rig
                    .finalize(session)
                    .books
                    .single()
                    .status shouldBe UploadedBookStatus.DUPLICATE
            }
        }

        test("a same-titled book by a different author is NOT a duplicate") {
            withUploadRig { rig ->
                rig.seedLibraryBook(relPath = "existing/genesis", title = "Genesis", author = "Bernard Beckett")

                val session = rig.newSession()
                rig.stage(session, "genesis/01.m4b", "a")
                rig.stageAbsMetadata(session, "genesis", title = "Genesis", author = "Poul Anderson")

                val book = rig.finalize(session).books.single()
                book.status shouldBe UploadedBookStatus.IMPORTED
                book.rootRelPath shouldBe "Poul Anderson/Genesis"
            }
        }

        test("an arrival never merges into an existing folder — it takes a suffixed path instead") {
            withUploadRig { rig ->
                // A folder already sits at the canonical target, but holds a DIFFERENT book, so
                // the duplicate check passes and the collision has to be resolved by path.
                Files.createDirectories(rig.libraryRoot.resolve("Poul Anderson/Genesis"))
                Files.writeString(rig.libraryRoot.resolve("Poul Anderson/Genesis/other.m4b"), "existing")

                val session = rig.newSession()
                rig.stage(session, "genesis/01.m4b", "a")
                rig.stageAbsMetadata(session, "genesis", title = "Genesis", author = "Poul Anderson")

                rig
                    .finalize(session)
                    .books
                    .single()
                    .rootRelPath shouldBe "Poul Anderson/Genesis (2)"
                rig.libraryFilesUnder("Poul Anderson/Genesis") shouldBe listOf("other.m4b")
            }
        }

        test("two loose files sharing one session-root cover: the cover goes with the first, both books land") {
            // The Grouper hands every loose single-file book the same root-level images, which is
            // right for a scan (nothing moves) and impossible for an upload (one destination per
            // file). The second book must still land, minus the cover — never fail on a source the
            // first book already took.
            withUploadRig { rig ->
                val session = rig.newSession()
                rig.stage(session, "Elantris.m4b", "a")
                rig.stage(session, "Warbreaker.m4b", "b")
                rig.stage(session, "cover.jpg", "img")

                val result = rig.finalize(session)

                result.books.size shouldBe 2
                result.books.all { it.status == UploadedBookStatus.IMPORTED } shouldBe true
                val paths = result.books.mapNotNull { it.rootRelPath }
                val withCover = paths.filter { rig.libraryFilesUnder(it).contains("cover.jpg") }
                withClue("exactly one book may own the shared cover: $paths") { withCover.size shouldBe 1 }
                paths.all { rig.libraryFilesUnder(it).any { name -> name.endsWith(".m4b") } } shouldBe true
            }
        }

        test("a session holding no audio is refused, and swept") {
            withUploadRig { rig ->
                val session = rig.newSession()
                rig.stage(session, "notes/readme.pdf", "pdf")

                val failure = rig.finalizeResult(session).shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<UploadError.NoBooksFound>()
                rig.sessionExists(session) shouldBe false
                rig.allLibraryFiles() shouldBe emptyList()
            }
        }
    })

// ── rig ─────────────────────────────────────────────────────────────────────────

/**
 * A real finalize pipeline over a temp home, a temp library root and a migrated database — every
 * collaborator is the production class except [UploadFinalizeRig.ingested], which records the
 * incremental-scan trigger instead of booting a scanner.
 */
private class UploadFinalizeRig(
    val sql: ListenUpDatabase,
    val libraryRoot: NioPath,
    val staging: UploadStaging,
    val finalizer: UploadFinalizer,
    val ingested: MutableList<IoPath>,
    private val bookRepository: BookRepository,
) {
    fun newSession(): String = staging.createSession()

    fun sessionExists(sessionId: String): Boolean = staging.openSession(sessionId) != null

    /** Writes one staged file at [relPath] within the session, exactly where an upload would put it. */
    fun stage(
        sessionId: String,
        relPath: String,
        content: String,
    ) {
        val dir = requireNotNull(staging.openSession(sessionId))
        val target = relPath.split('/').fold(dir) { acc, seg -> IoPath(acc, seg) }
        val part = staging.beginFile(target)
        Files.writeString(NioPath.of(part.toString()), content)
        staging.commitFile(part, target)
    }

    /** Writes an ABS `metadata.json` into a staged book folder — the highest-precedence scan source. */
    fun stageAbsMetadata(
        sessionId: String,
        bookFolder: String,
        title: String,
        author: String,
        series: String? = null,
        asin: String? = null,
    ) {
        val fields =
            buildList {
                add(jsonString("title", title))
                add(jsonArray("authors", author))
                if (series != null) add(jsonArray("series", series))
                if (asin != null) add(jsonString("asin", asin))
            }
        stage(sessionId, "$bookFolder/metadata.json", "{${fields.joinToString(",")}}")
    }

    fun finalizeResult(sessionId: String): AppResult<UploadFinalizeResult> {
        val sessionDir = requireNotNull(staging.openSession(sessionId))
        return runBlocking { finalizer.finalize(sessionId, sessionDir) }
    }

    fun finalize(sessionId: String): UploadFinalizeResult =
        finalizeResult(sessionId)
            .shouldBeInstanceOf<AppResult.Success<UploadFinalizeResult>>()
            .data

    /** Paths (relative to [relPath]) of every file under that library subdirectory. */
    fun libraryFilesUnder(relPath: String): List<String> {
        val base = libraryRoot.resolve(relPath)
        if (!Files.isDirectory(base)) return emptyList()
        return Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { base.relativize(it).toString() }.toList()
        }
    }

    /** Every file in the whole library root — the "nothing was left behind" assertion. */
    fun allLibraryFiles(): List<String> =
        Files.walk(libraryRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { libraryRoot.relativize(it).toString() }
                .toList()
                .sorted()
        }

    /**
     * Puts a book in the library the way a scan would: real files, real `Walker → Grouper →
     * Analyzer`, real [BookRepository.resolveOrInsert]. Nothing about the duplicate check is
     * proven by a hand-inserted row.
     */
    fun seedLibraryBook(
        relPath: String,
        title: String,
        author: String,
        asin: String? = null,
    ) {
        val dir = libraryRoot.resolve(relPath)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("01.m4b"), "seed")
        val fields =
            listOfNotNull(
                jsonString("title", title),
                jsonArray("authors", author),
                asin?.let { jsonString("asin", it) },
            )
        Files.writeString(dir.resolve("metadata.json"), "{${fields.joinToString(",")}}")
        scanAndIngestLibrary()
    }

    /** One real scan pass over the library root, ingesting every analyzed book. Returns the book ids. */
    fun scanAndIngestLibrary(): List<String> =
        runBlocking {
            val root = IoPath(libraryRoot.toString())
            val analyzer =
                Analyzer(
                    rootPath = root,
                    metadataReader = AbsMetadataReader(contractJson),
                    embeddedMetadataParser =
                        EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList()),
                )
            val analyzed: List<AnalyzedBook> =
                analyzer
                    .analyze(Grouper().group(Walker().walk(root)))
                    .toList()
                    .mapNotNull { it.getOrNull() }
            analyzed
                .map { book ->
                    bookRepository
                        .resolveOrInsert(
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            analyzed = book,
                            pendingCover = null,
                            systemCollectionId = null,
                            contributorIds = null,
                            seriesIds = null,
                        ).shouldBeInstanceOf<AppResult.Success<IngestOutcome>>()
                        .data
                        .bookId.value
                }
        }
}

/** Builds a [UploadFinalizeRig] over fresh temp directories and a migrated database, then tears it down. */
private fun withUploadRig(block: (UploadFinalizeRig) -> Unit) {
    withSqlDatabase {
        val homeDir = Files.createTempDirectory("listenup-upload-home-")
        val libraryRoot = Files.createTempDirectory("listenup-upload-library-")
        try {
            sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
            val ingested = mutableListOf<IoPath>()
            val staging = UploadStaging(UploadPaths(IoPath(homeDir.toString())))
            val bookRepository = makeBookRepository(this)
            val broker =
                LibraryWriteBroker(
                    SelfWriteRegistry { 0L },
                    WriteJournal(tempJournalDir()),
                    SqlLibraryRootProvider(sql),
                )
            val finalizer =
                UploadFinalizer(
                    staging = staging,
                    settingsStore =
                        OrganizerSettingsStore(ServerSettingsRepository(sql, default = RegistrationPolicy.CLOSED)),
                    duplicates = UploadDuplicateDetector(sql),
                    broker = broker,
                    libraryRegistry = LibraryRegistry(sql),
                    sql = sql,
                    metadataReader = AbsMetadataReader(contractJson),
                    embeddedMetadataParser =
                        EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList()),
                    listenUpSidecarReader = ListenUpSidecarReader(SidecarWriteStateRepository(sql)),
                    // Records the trigger instead of booting a scanner: what finalize owes the
                    // caller is that the files are in place and the request is told where.
                    ingest = { bookRoot -> ingested += bookRoot },
                )
            block(
                UploadFinalizeRig(
                    sql = sql,
                    libraryRoot = libraryRoot,
                    staging = staging,
                    finalizer = finalizer,
                    ingested = ingested,
                    bookRepository = bookRepository,
                ),
            )
        } finally {
            homeDir.toFile().deleteRecursively()
            libraryRoot.toFile().deleteRecursively()
        }
    }
}

private fun makeBookRepository(db: SqlTestDatabases): BookRepository {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    return BookRepository(
        db = db.sql,
        driver = db.driver,
        bus = bus,
        registry = registry,
        contributorRepository = ContributorRepository(db.sql, bus, registry),
        seriesRepository = SeriesRepository(db.sql, bus, registry),
        genreRepository = GenreRepository(db.sql, bus, registry),
    )
}

/** One ABS `metadata.json` scalar field, quoted — kept out of the call sites so no test escapes a quote. */
private fun jsonString(
    name: String,
    value: String,
): String = """"$name":"$value""""

/** One ABS `metadata.json` single-element array field. */
private fun jsonArray(
    name: String,
    value: String,
): String = """"$name":["$value"]"""
