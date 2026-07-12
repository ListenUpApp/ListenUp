package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.WriteJournal
import com.calypsan.listenup.server.librarywrite.tempJournalDir
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.pipeline.Analyzer
import com.calypsan.listenup.server.scanner.pipeline.Grouper
import com.calypsan.listenup.server.scanner.pipeline.Walker
import com.calypsan.listenup.server.scanner.watcher.FolderWatcher
import com.calypsan.listenup.server.scanner.watcher.StableSizeDebouncer
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.IngestOutcome
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path as IoPath
import kotlin.time.Duration.Companion.milliseconds

/**
 * [MoveManifestExecutor] — the phase's load-bearing component. Files move via
 * [LibraryWriteBroker] (journaled, watcher-suppressed), then the book's `root_rel_path` is
 * rewritten in the same logical step, so the scanner's disk-diff never sees the move at all
 * (spec §5): identity, positions, and collection membership are untouchable by design, not by
 * careful bookkeeping.
 */
class MoveManifestExecutorTest :
    FunSpec({
        val settings = OrganizerSettings(preset = StructurePreset.AUTHOR_TITLE)

        test("happy path: files land at the new location, DB updates, identity is untouched") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-executor-happy-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedCatalog(sql)
                sql.seedTestUser("u1")

                val bookDir = libraryRoot.resolve("messy").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")
                Files.writeString(bookDir.resolve("02.m4b"), "a")
                Files.writeString(bookDir.resolve("cover.jpg"), "a")

                runTest {
                    val repo = buildBookRepository(sql, driver)
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            rootRelPath = "messy",
                            contributors = listOf(authorPayload("c1", "Brandon Sanderson")),
                            audioFiles =
                                listOf(
                                    audioFilePayload("af1", "01.m4b"),
                                    audioFilePayload("af2", "02.m4b", index = 1),
                                ),
                        ),
                    )
                    seedPlaybackPosition(sql, userId = "u1", bookId = "b1")
                    seedCollectionMembership(sql, collectionId = "coll1", ownerId = "u1", bookId = "b1")
                    val membershipBefore = collectionIdsForBook(driver, "b1")
                    membershipBefore shouldBe listOf("coll1")

                    val plan = OrganizePlanBuilder(sql).build(LibraryId("test-library"), settings)
                    val entry = plan.entries.single()

                    val broker = LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir()))
                    val executor = MoveManifestExecutor(broker, repo)
                    val result = executor.execute(entry)

                    result shouldBe AppResult.Success(Unit)

                    val newDir = libraryRoot.resolve("Brandon Sanderson/The Way of Kings")
                    newDir.resolve("01.m4b").toFile().exists() shouldBe true
                    newDir.resolve("02.m4b").toFile().exists() shouldBe true
                    newDir.resolve("cover.jpg").toFile().exists() shouldBe true
                    bookDir.toFile().exists() shouldBe false

                    val row = sql.booksQueries.selectById("b1").executeAsOne()
                    row.root_rel_path shouldBe "Brandon Sanderson/The Way of Kings"
                    row.id shouldBe "b1"

                    val position = sql.playbackPositionsQueries.selectLiveForUserBook("u1", "b1").executeAsOne()
                    position.position_ms shouldBe 42_000L

                    val membershipAfter = collectionIdsForBook(driver, "b1")
                    membershipAfter shouldBe membershipBefore
                }
            }
        }

        test("retrying a manifest after a broker failure completes cleanly with exactly one copy of every file") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-executor-retry-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedCatalog(sql)

                val bookDir = libraryRoot.resolve("messy").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")
                Files.writeString(bookDir.resolve("02.m4b"), "a")
                Files.writeString(bookDir.resolve("03.m4b"), "a")

                runTest {
                    val repo = buildBookRepository(sql, driver)
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            rootRelPath = "messy",
                            contributors = listOf(authorPayload("c1", "Brandon Sanderson")),
                            audioFiles =
                                listOf(
                                    audioFilePayload("af1", "01.m4b"),
                                    audioFilePayload("af2", "02.m4b", index = 1),
                                    audioFilePayload("af3", "03.m4b", index = 2),
                                ),
                        ),
                    )

                    val plan = OrganizePlanBuilder(sql).build(LibraryId("test-library"), settings)
                    val entry = plan.entries.single()

                    // Pre-create an AMBIGUOUS state for the second file: both the source and the
                    // destination already exist. WriteOp.MoveFile treats this as an unrecoverable
                    // typed failure (rather than guessing), so the manifest stops after file 1 and
                    // never reaches file 3 or the DB write — simulating a broker-level partial failure
                    // without needing a fault-injection hook.
                    val newDir = libraryRoot.resolve("Brandon Sanderson/The Way of Kings")
                    Files.createDirectories(newDir)
                    Files.writeString(newDir.resolve("02.m4b"), "already-there")

                    val broker = LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir()))
                    val executor = MoveManifestExecutor(broker, repo)

                    val firstAttempt = executor.execute(entry)
                    firstAttempt.shouldBeInstanceOf<AppResult.Failure>()
                    // The DB was never touched — the broker failed before the executor's DB step ran.
                    sql.booksQueries
                        .selectById("b1")
                        .executeAsOne()
                        .root_rel_path shouldBe "messy"

                    // Resolve the ambiguity the way an operator/retry would: the source copy is the
                    // one still tracked as "the file" (mtime is newer at the tracked source in a
                    // real crash — here we simply remove the stray pre-existing destination copy).
                    newDir.resolve("02.m4b").toFile().delete()

                    val secondAttempt = executor.execute(entry)
                    secondAttempt shouldBe AppResult.Success(Unit)

                    newDir.resolve("01.m4b").toFile().exists() shouldBe true
                    newDir.resolve("02.m4b").toFile().exists() shouldBe true
                    newDir.resolve("03.m4b").toFile().exists() shouldBe true
                    bookDir.toFile().exists() shouldBe false
                    // Exactly one copy — the retry didn't duplicate the already-moved file 1.
                    newDir.toFile().listFiles()?.size shouldBe 3

                    sql.booksQueries
                        .selectById("b1")
                        .executeAsOne()
                        .root_rel_path shouldBe
                        "Brandon Sanderson/The Way of Kings"
                }
            }
        }

        test("watcher silence: executing a manifest fires zero watcher events") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-executor-watcher-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedCatalog(sql)

                val bookDir = libraryRoot.resolve("messy").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")

                runBlocking {
                    val repo = buildBookRepository(sql, driver)
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            rootRelPath = "messy",
                            contributors = listOf(authorPayload("c1", "Brandon Sanderson")),
                            audioFiles = listOf(audioFilePayload("af1", "01.m4b")),
                        ),
                    )

                    val plan = OrganizePlanBuilder(sql).build(LibraryId("test-library"), settings)
                    val entry = plan.entries.single()

                    val registry = SelfWriteRegistry { System.currentTimeMillis() }
                    val broker = LibraryWriteBroker(registry, WriteJournal(tempJournalDir()), suppressionTtlMs = 30_000)
                    val executor = MoveManifestExecutor(broker, repo)

                    withSuppressingWatcher(libraryRoot.toString(), registry) { watcher ->
                        val emissions = mutableListOf<IoPath>()
                        val collector =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                watcher.events.collect { emissions.add(it) }
                            }

                        val result = executor.execute(entry)
                        result shouldBe AppResult.Success(Unit)
                        delay(1_500) // settle window + inotify latency

                        withClue("organize move must never wake the watcher. emissions: $emissions") {
                            emissions.size shouldBe 0
                        }

                        collector.cancel()
                    }
                }
            }
        }

        test("identity invariant: a real rescan of the new location resolves to the SAME bookId, not a new one") {
            audioLibrary { book("messy") { tracks(count = 2) } }.use { fixture ->
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder(folderPath = fixture.root.toString())
                    seedCatalog(sql)

                    runTest {
                        val repo = buildBookRepository(sql, driver)
                        repo.upsert(
                            bookPayloadFixture(
                                id = "b1",
                                title = "The Way of Kings",
                                rootRelPath = "messy",
                                contributors = listOf(authorPayload("c1", "Brandon Sanderson")),
                                audioFiles =
                                    listOf(
                                        audioFilePayload("af1", "01.m4b"),
                                        audioFilePayload("af2", "02.m4b", index = 1),
                                    ),
                            ),
                        )

                        val plan = OrganizePlanBuilder(sql).build(LibraryId("test-library"), settings)
                        val entry = plan.entries.single()
                        val broker = LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir()))
                        val executor = MoveManifestExecutor(broker, repo)
                        executor.execute(entry) shouldBe AppResult.Success(Unit)

                        // A REAL Walker -> Grouper -> Analyzer pass over the moved library root — exactly
                        // what a normal rescan would produce, no organizer knowledge involved.
                        val libraryPath = IoPath(fixture.root.toString())
                        val metadataReader = AbsMetadataReader(contractJson)
                        val embeddedParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList())
                        val files = Walker().walk(libraryPath)
                        val candidates = Grouper().group(files)
                        val analyzer = Analyzer(libraryPath, metadataReader, embeddedParser)
                        val analyzed = analyzer.analyze(candidates).toList().mapNotNull { it.getOrNull() }

                        val movedBook = analyzed.single { it.candidate.rootRelPath == entry.toRootRelPath }

                        // The natural-key path-lookup in resolveOrInsert must hit on the FIRST branch —
                        // the whole point of updating the DB in the same logical step as the disk move —
                        // so the same bookId comes back and wasNew is false. If the organizer's DB write
                        // had been skipped, this would instead fall through to the inode-match branch
                        // (still same-book, thanks to the scanner's own move-detection) or, worse, mint a
                        // brand-new UUID.
                        val outcome =
                            repo.resolveOrInsert(
                                libraryId = LibraryId("test-library"),
                                folderId = FolderId("test-folder"),
                                analyzed = movedBook,
                                pendingCover = null,
                                systemCollectionId = null,
                                contributorIds = null,
                                seriesIds = null,
                            )

                        val success = outcome.shouldBeInstanceOf<AppResult.Success<IngestOutcome>>()
                        success.data.bookId shouldBe BookId("b1")
                        success.data.wasNew shouldBe false
                    }
                }
            }
        }
    })

private suspend fun withSuppressingWatcher(
    libraryRoot: String,
    registry: SelfWriteRegistry,
    block: suspend (FolderWatcher) -> Unit,
) {
    val supervisor = SupervisorJob()
    val scope = CoroutineScope(supervisor + Dispatchers.Default)
    val watcher =
        FolderWatcher(
            libraryRoot = IoPath(libraryRoot),
            scope = scope,
            debouncer = StableSizeDebouncer(settle = 50.milliseconds, poll = 25.milliseconds),
            selfWrites = registry,
        )
    try {
        watcher.start()
        delay(150)
        block(watcher)
    } finally {
        watcher.close()
        scope.cancel()
    }
}

/**
 * Every LIVE `collection_books.collection_id` for [bookId], via a raw query — no
 * `selectByBookId` query exists on `CollectionBooksQueries` (its lookups are keyed by the
 * synthetic `id`, not by book), so this reads the column set directly for the before/after
 * membership comparison.
 */
private fun collectionIdsForBook(
    driver: app.cash.sqldelight.db.SqlDriver,
    bookId: String,
): List<String> {
    val result = mutableListOf<String>()
    driver.executeQuery(
        identifier = null,
        sql = "SELECT collection_id FROM collection_books WHERE book_id = ? AND deleted_at IS NULL ORDER BY collection_id",
        parameters = 1,
        binders = { bindString(0, bookId) },
        mapper = { cursor ->
            while (cursor.next().value) {
                cursor.getString(0)?.let { result += it }
            }
            app.cash.sqldelight.db.QueryResult
                .Value(Unit)
        },
    )
    return result
}

private fun seedCollectionMembership(
    sql: ListenUpDatabase,
    collectionId: String,
    ownerId: String,
    bookId: String,
) {
    sql.transaction {
        sql.collectionsQueries.insert(
            id = collectionId,
            library_id = "test-library",
            owner_id = ownerId,
            name = "Test Collection",
            type = "CUSTOM",
            created_at = 1L,
            updated_at = 1L,
            revision = 0L,
            deleted_at = null,
            client_op_id = null,
        )
        sql.collectionBooksQueries.insert(
            id = "$collectionId:$bookId",
            collection_id = collectionId,
            book_id = bookId,
            created_at = 1L,
            updated_at = 1L,
            revision = 0L,
            deleted_at = null,
            client_op_id = null,
        )
    }
}

private fun seedPlaybackPosition(
    sql: ListenUpDatabase,
    userId: String,
    bookId: String,
) {
    sql.transaction {
        sql.playbackPositionsQueries.insert(
            id = "$userId:$bookId",
            user_id = userId,
            book_id = bookId,
            position_ms = 42_000L,
            max_position_ms = 42_000L,
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

/** Seeds the `contributors`/`book_series` catalogue rows the junction-row FKs require. */
private fun seedCatalog(sql: ListenUpDatabase) {
    sql.transaction {
        sql.contributorsQueries.insert(
            id = "c1",
            normalized_name = "brandon sanderson",
            name = "Brandon Sanderson",
            sort_name = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
            asin = null,
            description = null,
            image_path = null,
            image_blur_hash = null,
            birth_date = null,
            death_date = null,
            website = null,
        )
    }
}

private fun buildBookRepository(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): BookRepository {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = registry,
        contributorRepository = ContributorRepository(sql, bus, registry),
        seriesRepository = SeriesRepository(sql, bus, registry),
        genreRepository = GenreRepository(sql, bus, registry),
    )
}

private fun authorPayload(
    id: String,
    name: String,
) = com.calypsan.listenup.api.sync
    .BookContributorPayload(id = id, name = name, sortName = null, role = "author", creditedAs = null)

private fun audioFilePayload(
    id: String,
    filename: String,
    index: Int = 0,
) = com.calypsan.listenup.api.sync.BookAudioFilePayload(
    id = id,
    index = index,
    filename = filename,
    format = "m4b",
    codec = "aac",
    duration = 1_000L,
    size = 1_000L,
)
