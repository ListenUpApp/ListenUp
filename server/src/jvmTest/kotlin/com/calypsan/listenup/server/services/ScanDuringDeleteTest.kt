package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
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
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testLibrary
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists

/**
 * A full scan and a Delete Book racing each other: the scan walks a book, the persister checks the
 * walk against the disk (the book's audio is still there), and the admin's delete removes the
 * book's folder and tombstones its row before the persister writes the walked book. The book must
 * stay deleted: a persist that wrote the stale walk would revive the tombstoned row, bringing the
 * book back on every device with no files behind it until a later scan swept it again.
 *
 * The walk comes from a freshly started scanner (a server restart forgets its last result), so the
 * book is Added and the persister really writes it; a scanner that knew the book would see it
 * unchanged and write nothing.
 *
 * [LibraryLayoutLock] is what closes the window: [BookDeleter] holds it, as the persister does, so
 * the delete waits until the persist is done and then removes the book for good.
 */
class ScanDuringDeleteTest :
    FunSpec({
        test("a book deleted while a scan persists its walk stays deleted") {
            deleteRaceFixture { rig ->
                val walked = scannerFor(rig.fixture).runFullScan()
                val probed = CompletableDeferred<Unit>()
                val deleteFinished = CountDownLatch(1)
                rig.onFirstProbe {
                    probed.complete(Unit)
                    // Holds the persister between its disk check and its writes until the delete is
                    // done — or, when a lock keeps the delete out, long enough that it plainly waited.
                    deleteFinished.await(DELETE_HELD_OFF_MS, TimeUnit.MILLISECONDS)
                }

                // Real threads: the persister blocks inside its disk probe while the delete runs.
                withContext(Dispatchers.Default) {
                    val delete =
                        launch {
                            probed.await()
                            rig.deleter.delete(BookId(rig.originalId)) shouldBe AppResult.Success(Unit)
                            deleteFinished.countDown()
                        }
                    rig.persister.persist(walked)
                    delete.join()
                }

                withClue("the book's folder is gone") {
                    rig.fixture.root
                        .resolve(BOOK_DIR)
                        .exists() shouldBe false
                }
                val live =
                    rig.sql.booksQueries
                        .selectLiveIdsAndPathsForLibrary(LIBRARY_ID)
                        .executeAsList()
                withClue("live books after the race: ${live.map { it.id to it.root_rel_path }}") {
                    live shouldBe emptyList()
                }
                rig.sql.booksQueries
                    .selectById(rig.originalId)
                    .executeAsOneOrNull()
                    .shouldNotBeNull()
                    .deleted_at
                    .shouldNotBeNull()
            }
        }
    })

private const val LIBRARY_ID = "test-library"
private const val BOOK_DIR = "messy"
private const val DELETE_HELD_OFF_MS = 500L

private class DeleteRaceRig(
    val fixture: AudioLibraryFixture,
    val sql: ListenUpDatabase,
    val persister: BookPersister,
    val deleter: BookDeleter,
    val originalId: String,
    private val probeHook: ProbeHook,
) {
    fun onFirstProbe(action: () -> Unit) = probeHook.arm(action)
}

/** The persister's disk probe, with a one-shot action run after the real check. */
private class ProbeHook {
    private val armed = AtomicBoolean(false)
    private var action: () -> Unit = {}

    fun arm(onProbe: () -> Unit) {
        action = onProbe
        armed.set(true)
    }

    fun probe(path: Path): Boolean {
        val present = SystemFileSystem.exists(path)
        if (armed.compareAndSet(true, false)) action()
        return present
    }
}

private fun deleteRaceFixture(block: suspend (DeleteRaceRig) -> Unit) {
    audioLibrary {
        book(BOOK_DIR) {
            tracks(count = 2)
            metadataJson("""{"title":"The Way of Kings","authors":["Brandon Sanderson"]}""")
        }
    }.use { fixture ->
        withSqlDatabase {
            sql.seedTestLibraryAndFolder(libraryId = LIBRARY_ID, folderPath = fixture.root.toString())
            runTest {
                val layoutLock = LibraryLayoutLock()
                val probeHook = ProbeHook()
                val repo = bookRepository()
                val persister = persister(repo, layoutLock, this, probeHook)
                val broker =
                    LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir()), SqlLibraryRootProvider(sql))
                val deleter = BookDeleter(sql, repo, broker, layoutLock)

                persister.persist(scannerFor(fixture).runFullScan())
                val originalId =
                    sql.booksQueries
                        .selectLiveIdsAndPathsForLibrary(LIBRARY_ID)
                        .executeAsList()
                        .single()
                        .id
                block(DeleteRaceRig(fixture, sql, persister, deleter, originalId, probeHook))
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
    scope: CoroutineScope,
    probeHook: ProbeHook,
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
        isOnDisk = probeHook::probe,
    )
