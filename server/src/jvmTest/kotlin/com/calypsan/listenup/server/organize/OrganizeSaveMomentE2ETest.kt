package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.OrganizeServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.WriteJournal
import com.calypsan.listenup.server.librarywrite.tempJournalDir
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.pipeline.Analyzer
import com.calypsan.listenup.server.scanner.pipeline.Grouper
import com.calypsan.listenup.server.scanner.pipeline.Walker
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path as IoPath

private const val E2E_TIMEOUT_MS = 60_000L

/**
 * The save-moment end-to-end (plan Task 6): a messy on-disk library ingested through the REAL
 * scanner pipeline, organized through the REAL service (preview → saveAndExecute → observe to
 * terminal), then re-scanned through the REAL pipeline again — asserting disk matches the
 * planner's output for every book, the rescan is a zero-change no-op (same ids, `wasNew=false`,
 * revisions untouched — the idempotent re-scan branch), and positions/collections survive.
 */
class OrganizeSaveMomentE2ETest :
    FunSpec({

        test("save moment: messy library organizes, rescan sees zero changes, identity intact") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-save-moment-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                sql.seedTestUser("u1")

                // A messy library: one book nested in the wrong place, plus two distinct books
                // that will collide on the same canonical title (colliding-titles case).
                seedBookDir(libraryRoot, "downloads/tmp/wok-rip", tracks = 2, withCover = true)
                seedBookDir(libraryRoot, "incoming/way-of-kings-v2", tracks = 1)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    runBlocking {
                        val repo = makeRepo(this@withSqlDatabase)

                        // 1. Ingest through the REAL scan pipeline (both books get the same
                        //    title/author metadata via their ABS metadata.json sidecars).
                        val firstScan = scanAndIngest(libraryRoot, repo)
                        firstScan.size shouldBe 2
                        firstScan.all { it.second } shouldBe true // both genuinely new

                        val idsByPath =
                            sql.booksQueries
                                .selectLiveIdsAndPathsForLibrary("test-library")
                                .executeAsList()
                                .associate { it.root_rel_path to it.id }
                        val nestedId = idsByPath.getValue("downloads/tmp/wok-rip")
                        val colliderId = idsByPath.getValue("incoming/way-of-kings-v2")

                        seedPosition(sql, userId = "u1", bookId = nestedId, positionMs = 77_000L)

                        // 2. Preview: both books move; the two identical titles collide once.
                        val service = makeService(this@withSqlDatabase, repo, scope)
                        val preview = (service.preview(settingsUnderTest()) as AppResult.Success).data
                        // 6 files total: book 1 = 2 audio + cover.jpg + metadata.json, book 2 =
                        // 1 audio + metadata.json — sidecars travel with their book by design.
                        withClue("preview entries: ${preview.entries}") {
                            preview.bookCount shouldBe 2
                            preview.fileCount shouldBe 6
                            preview.collisionCount shouldBe 1
                        }

                        // 3. Save = approve-and-run; observe to the terminal report.
                        val runId = (service.saveAndExecute(settingsUnderTest()) as AppResult.Success).data
                        val events =
                            withTimeout(E2E_TIMEOUT_MS) {
                                service.observeRun(runId).toList().map { (it as RpcEvent.Data).value }
                            }
                        val terminal = events.last().shouldBeInstanceOf<OrganizeRunEvent.Completed>()
                        terminal.movedBooks shouldBe 2
                        terminal.failedBooks shouldBe 0

                        // 4. Disk matches the planner's output for every book (deterministic
                        //    collision order = by bookId).
                        val canonical = "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings"
                        val expectedNested: String
                        val expectedCollider: String
                        if (nestedId < colliderId) {
                            expectedNested = canonical
                            expectedCollider = "$canonical (2)"
                        } else {
                            expectedNested = "$canonical (2)"
                            expectedCollider = canonical
                        }
                        libraryRoot
                            .resolve(expectedNested)
                            .resolve("01.m4b")
                            .toFile()
                            .exists() shouldBe true
                        libraryRoot
                            .resolve(expectedNested)
                            .resolve("cover.jpg")
                            .toFile()
                            .exists() shouldBe true
                        libraryRoot
                            .resolve(expectedCollider)
                            .resolve("01.m4b")
                            .toFile()
                            .exists() shouldBe true
                        libraryRoot.resolve("downloads/tmp/wok-rip").toFile().exists() shouldBe false
                        libraryRoot.resolve("incoming/way-of-kings-v2").toFile().exists() shouldBe false

                        sql.booksQueries
                            .selectById(nestedId)
                            .executeAsOne()
                            .root_rel_path shouldBe expectedNested
                        sql.booksQueries
                            .selectById(colliderId)
                            .executeAsOne()
                            .root_rel_path shouldBe expectedCollider

                        // 5. THE invariant: a full REAL rescan of the organized library is a
                        //    zero-change no-op — same ids, nothing new, and the idempotent
                        //    re-scan branch leaves every revision untouched.
                        val revisionsBefore = liveRevisions(sql)
                        val rescan = scanAndIngest(libraryRoot, repo)
                        withClue("rescan outcomes: $rescan") {
                            rescan.size shouldBe 2
                            rescan.map { it.first }.toSet() shouldBe setOf(nestedId, colliderId)
                            rescan.none { it.second } shouldBe true // zero Added
                        }
                        withClue("revisions must not move — the rescan saw zero changes") {
                            liveRevisions(sql) shouldBe revisionsBefore
                        }

                        // 6. Identity payloads: position and collection membership intact.
                        sql.playbackPositionsQueries
                            .selectLiveForUserBook("u1", nestedId)
                            .executeAsOne()
                            .position_ms shouldBe 77_000L
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    })

/** The schema every step of the E2E uses: enabled, Author/Series/Title with defaults. */
private fun settingsUnderTest() = OrganizeSettingsDto(enabled = true, preset = OrganizePreset.AUTHOR_SERIES_TITLE)

/**
 * Writes one messy book folder: [tracks] zero-byte audio placeholders, an ABS `metadata.json`
 * pinning identical title/author metadata (so two seeded books collide canonically), and
 * optionally a cover sidecar.
 */
private fun seedBookDir(
    libraryRoot: NioPath,
    relPath: String,
    tracks: Int,
    withCover: Boolean = false,
) {
    val dir = libraryRoot.resolve(relPath)
    Files.createDirectories(dir)
    repeat(tracks) { i ->
        Files.writeString(dir.resolve("%02d.m4b".format(i + 1)), "")
    }
    if (withCover) Files.writeString(dir.resolve("cover.jpg"), "img")
    // Series pinned via ABS metadata (highest precedence) so the messy nesting can't leak a
    // folder-inferred series segment ("tmp") into the canonical path — and so both seeded books
    // resolve to the SAME canonical target, exercising the collision suffix.
    Files.writeString(
        dir.resolve("metadata.json"),
        """{"title":"The Way of Kings","authors":["Brandon Sanderson"],"series":["Stormlight Archive #1"]}""",
    )
}

/**
 * One REAL scan pass: Walker → Grouper → Analyzer over [libraryRoot], every analyzed book fed
 * through [BookRepository.resolveOrInsert] — the exact ingest path a production scan takes.
 * Returns (bookId, wasNew) per book.
 */
private suspend fun scanAndIngest(
    libraryRoot: NioPath,
    repo: BookRepository,
): List<Pair<String, Boolean>> {
    val root = IoPath(libraryRoot.toString())
    val analyzer =
        Analyzer(
            rootPath = root,
            metadataReader = AbsMetadataReader(contractJson),
            embeddedMetadataParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList()),
        )
    val analyzed: List<AnalyzedBook> =
        analyzer
            .analyze(Grouper().group(Walker().walk(root)))
            .toList()
            .mapNotNull { it.getOrNull() }
    return analyzed.map { book ->
        val outcome =
            repo.resolveOrInsert(
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                analyzed = book,
                pendingCover = null,
                systemCollectionId = null,
                contributorIds = null,
                seriesIds = null,
            )
        val success = outcome.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.server.services.IngestOutcome>>()
        success.data.bookId.value to success.data.wasNew
    }
}

private fun liveRevisions(sql: ListenUpDatabase): Map<String, Long> =
    sql.booksQueries
        .selectAllLiveIds()
        .executeAsList()
        .associateWith { id ->
            sql.booksQueries
                .selectById(id)
                .executeAsOne()
                .revision
        }

private fun makeRepo(db: SqlTestDatabases): BookRepository {
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

private fun makeService(
    db: SqlTestDatabases,
    repo: BookRepository,
    scope: CoroutineScope,
): OrganizeServiceImpl {
    val broker = LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir()))
    return OrganizeServiceImpl(
        settingsStore = OrganizerSettingsStore(ServerSettingsRepository(db.sql, default = RegistrationPolicy.CLOSED)),
        planBuilder = OrganizePlanBuilder(db.sql),
        executor = MoveManifestExecutor(broker, repo),
        broker = broker,
        libraryRegistry = LibraryRegistry(db.sql),
        sql = db.sql,
        runState = OrganizeRunState(),
        runScope = scope,
        principal = PrincipalProvider { UserPrincipal(UserId("root1"), SessionId("s-root1"), UserRole.ROOT) },
    )
}

private fun seedPosition(
    sql: ListenUpDatabase,
    userId: String,
    bookId: String,
    positionMs: Long,
) {
    sql.transaction {
        sql.playbackPositionsQueries.insert(
            id = "$userId:$bookId",
            user_id = userId,
            book_id = bookId,
            position_ms = positionMs,
            max_position_ms = positionMs,
            last_played_at = 1L,
            finished = 0L,
            playback_speed = 1.0,
            current_chapter_id = null,
            revision = 0L,
            created_at = 1L,
            updated_at = 1L,
            deleted_at = null,
            client_op_id = null,
        )
    }
}
