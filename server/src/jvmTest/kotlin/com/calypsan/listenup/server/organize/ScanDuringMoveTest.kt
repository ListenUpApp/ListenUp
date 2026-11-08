package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.SqlLibraryRootProvider
import com.calypsan.listenup.server.librarywrite.WriteJournal
import com.calypsan.listenup.server.librarywrite.tempJournalDir
import com.calypsan.listenup.server.scanner.AudioLibraryFixture
import com.calypsan.listenup.server.scanner.Scanner
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.LibraryLayoutLock
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.inertCollectionService
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testLibrary
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.path.exists
import kotlinx.coroutines.test.runTest

/**
 * A full scan and an organizer move racing each other: the scan walks a book at its old folder,
 * the organizer moves it (files and `root_rel_path`) before the scan persists, and only then does
 * the scan persist and sweep. The book must come out the other side as the SAME book — same id,
 * live, at its new path, its listening position intact — and no second book may appear.
 *
 * Both Differ outcomes for the walked book are covered: a scanner that already knows the book sees
 * it unchanged (nothing to write, so only the sweep can hurt it), and a freshly started scanner
 * (a server restart forgets its last result) sees it Added at a path that no longer exists.
 *
 * [MoveManifestExecutor] is the single step both the organize run and the edit relocator move a
 * book through, so driving it directly covers both callers.
 */
class ScanDuringMoveTest :
    FunSpec({
        val settings = OrganizerSettings(preset = StructurePreset.AUTHOR_TITLE)

        test("a book moved after the walk saw it keeps its id when a scanner that knew it persists the stale walk") {
            raceFixture { rig ->
                val walked = rig.scanner.runFullScan()
                rig.moveTheBook(settings)
                rig.persister.persist(walked)
                rig.shouldStillBeTheOriginalBook("after the racing scan persisted")

                rig.persister.persist(rig.scanner.runFullScan())
                rig.shouldStillBeTheOriginalBook("after the next scan")
            }
        }

        test("a book moved after the walk saw it keeps its id when a freshly started scanner persists the stale walk") {
            raceFixture { rig ->
                val restarted = rig.newScanner()
                val walked = restarted.runFullScan()
                rig.moveTheBook(settings)
                rig.persister.persist(walked)
                rig.shouldStillBeTheOriginalBook("after the racing scan persisted")

                rig.persister.persist(restarted.runFullScan())
                rig.shouldStillBeTheOriginalBook("after the next scan")
            }
        }

        test("a move waits while a persist holds the layout lock") {
            raceFixture { rig ->
                val entry = rig.planTheMove(settings)
                // Real threads and a real clock: the move does real I/O, so "it did not happen" needs a
                // real wait, long enough for an unguarded move of two empty files to finish many times over.
                withContext(Dispatchers.Default) {
                    lateinit var move: Job
                    rig.layoutLock.withLock {
                        move = launch { rig.executor.execute(entry) shouldBe AppResult.Success(Unit) }
                        withClue("the move must wait while the lock is held") {
                            withTimeoutOrNull(MOVE_HELD_OFF_MS) { move.join() } shouldBe null
                            rig.fixture.root
                                .resolve("messy")
                                .exists() shouldBe true
                        }
                    }
                    move.join()
                }
                rig.fixture.root
                    .resolve(entry.toRootRelPath)
                    .exists() shouldBe true
            }
        }
    })

private const val LIBRARY_ID = "test-library"
private const val USER_ID = "u1"
private const val POSITION_MS = 42_000L
private const val MOVE_HELD_OFF_MS = 500L

/** Everything one race needs: a real scanner, persister and executor over one temp library. */
private class RaceRig(
    val fixture: AudioLibraryFixture,
    val sql: ListenUpDatabase,
    val repo: BookRepository,
    val persister: BookPersister,
    val executor: MoveManifestExecutor,
    val layoutLock: LibraryLayoutLock,
    val originalId: String,
) {
    val scanner: Scanner = newScanner()
    var movedTo: String? = null

    fun newScanner(): Scanner = scannerFor(fixture)

    suspend fun planTheMove(settings: OrganizerSettings): MovePlanEntry =
        OrganizePlanBuilder(sql).build(LibraryId(LIBRARY_ID), settings).entries.single()

    suspend fun moveTheBook(settings: OrganizerSettings) {
        val entry = planTheMove(settings)
        executor.execute(entry) shouldBe AppResult.Success(Unit)
        movedTo = entry.toRootRelPath
    }

    fun shouldStillBeTheOriginalBook(moment: String) {
        withClue(moment) {
            val live = sql.booksQueries.selectLiveIdsAndPathsForLibrary(LIBRARY_ID).executeAsList()
            withClue("live books: ${live.map { it.id to it.root_rel_path }}") {
                live.map { it.id } shouldBe listOf(originalId)
                live.single().root_rel_path shouldBe movedTo
            }
            sql.playbackPositionsQueries
                .selectLiveForUserBook(USER_ID, originalId)
                .executeAsOneOrNull()
                ?.position_ms shouldBe POSITION_MS
        }
    }
}

private fun raceFixture(block: suspend (RaceRig) -> Unit) {
    audioLibrary {
        book("messy") {
            tracks(count = 2)
            metadataJson("""{"title":"The Way of Kings","authors":["Brandon Sanderson"]}""")
        }
    }.use { fixture ->
        withSqlDatabase {
            sql.seedTestLibraryAndFolder(libraryId = LIBRARY_ID, folderPath = fixture.root.toString())
            sql.seedTestUser(USER_ID)
            runTest {
                val layoutLock = LibraryLayoutLock()
                val repo = bookRepository()
                val persister = persister(repo, layoutLock, this)
                val broker = LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir()), SqlLibraryRootProvider(sql))
                val executor = MoveManifestExecutor(broker, repo, layoutLock)

                persister.persist(scannerFor(fixture).runFullScan())
                val originalId =
                    sql.booksQueries
                        .selectLiveIdsAndPathsForLibrary(LIBRARY_ID)
                        .executeAsList()
                        .single()
                        .id
                seedPlaybackPosition(sql, originalId)

                val rig =
                    RaceRig(
                        fixture = fixture,
                        sql = sql,
                        repo = repo,
                        persister = persister,
                        executor = executor,
                        layoutLock = layoutLock,
                        originalId = originalId,
                    )
                // The rig's own scanner must know the book, as a long-running server's would.
                rig.scanner.runFullScan()
                block(rig)
            }
        }
    }
}

private fun scannerFor(fixture: AudioLibraryFixture): Scanner =
    Scanner(
        library = testLibrary(id = LIBRARY_ID, folders = listOf(fixture.root.toString())),
        metadataReader = AbsMetadataReader(contractJson),
        embeddedMetadataParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList()),
        eventBus = MutableSharedFlow(replay = 64, extraBufferCapacity = 64),
        scanResultBus = MutableSharedFlow(replay = 1),
    )

private fun SqlTestDatabases.bookRepository(): BookRepository {
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
        collectionBookRepository = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
    )
}

private fun SqlTestDatabases.persister(
    repo: BookRepository,
    layoutLock: LibraryLayoutLock,
    scope: kotlinx.coroutines.CoroutineScope,
): BookPersister =
    BookPersister(
        ingest = repo,
        libraryRegistry = LibraryRegistry(sql),
        libraryRepository = LibraryRepository(sql, ChangeBus(), SyncRegistry()),
        collectionService = inertCollectionService(),
        sql = sql,
        scanResultBus = MutableSharedFlow<ScanResult>(),
        eventBus = MutableSharedFlow(),
        changeBus = ChangeBus(),
        scope = scope,
        layoutLock = layoutLock,
    )

private fun seedPlaybackPosition(
    sql: ListenUpDatabase,
    bookId: String,
) {
    sql.playbackPositionsQueries.insert(
        id = "$USER_ID:$bookId",
        user_id = USER_ID,
        book_id = bookId,
        position_ms = POSITION_MS,
        last_played_at = 1L,
        finished = 0L,
        playback_speed = 1.0,
        volume_boost_db = 0.0,
        measured_gain_db = null,
        finished_at = null,
        has_custom_speed = 0L,
        has_custom_boost = 0L,
        current_chapter_id = null,
        revision = 0L,
        created_at = 1L,
        updated_at = 1L,
        deleted_at = null,
        client_op_id = null,
    )
}
