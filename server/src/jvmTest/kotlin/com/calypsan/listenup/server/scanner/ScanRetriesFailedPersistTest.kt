package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.services.BookIngestPort
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.LibraryLayoutLock
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.services.PersistResult
import com.calypsan.listenup.server.services.ScanIdentityMaps
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.inertCollectionService
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testLibrary
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

/**
 * The scanner records a scan as "what the library looks like now" before the persister has written
 * it. A book whose write failed must still be written by the next scan — even though its files are
 * unchanged, so the scanner's fingerprint cache and diff would otherwise call it "already seen".
 * Real [Scanner], real [BookPersister], real [BookRepository]; one book's first write fails.
 */
class ScanRetriesFailedPersistTest :
    FunSpec({

        test("a book whose write failed is written by the next scan of unchanged files") {
            audioLibrary("listenup-190b-") {
                book(FLAKY) { tracks(count = 1) }
                book("Author/Steady") { tracks(count = 1) }
            }.use { fixture ->
                withSqlDatabase {
                    sql.seedTestLibraryAndFolder(libraryId = LIBRARY_ID, folderPath = fixture.root.toString())
                    runTest {
                        val retries = PersistRetryLedger()
                        val persister = persister(FailsOnceFor(FLAKY, bookRepository()), this, retries)
                        val scanner = scanner(fixture, retries)

                        persister.persist(scanner.runFullScan())
                        livePaths() shouldContainExactly listOf("Author/Steady")

                        persister.persist(scanner.runFullScan())
                        livePaths() shouldContainExactlyInAnyOrder listOf(FLAKY, "Author/Steady")
                    }
                }
            }
        }
    })

private const val LIBRARY_ID = "test-library"
private const val FLAKY = "Author/Flaky"

/** A real ingest whose first write of [rootRelPath] fails, the way a per-book transaction fault does. */
private class FailsOnceFor(
    private val rootRelPath: String,
    private val real: BookIngestPort,
) : BookIngestPort by real {
    private var failed = false

    override suspend fun resolveOrInsertAll(
        libraryId: LibraryId,
        folderId: FolderId,
        books: List<AnalyzedBook>,
        coversByBook: Map<String, PendingCover>,
        systemCollectionId: String?,
        identityMaps: ScanIdentityMaps,
        onProgress: suspend (processed: Int, failed: Int) -> Unit,
    ): PersistResult {
        val (doomed, rest) = books.partition { !failed && it.candidate.rootRelPath == rootRelPath }
        if (doomed.isEmpty()) {
            return real.resolveOrInsertAll(libraryId, folderId, books, coversByBook, systemCollectionId, identityMaps, onProgress)
        }
        failed = true
        val written = real.resolveOrInsertAll(libraryId, folderId, rest, coversByBook, systemCollectionId, identityMaps, onProgress)
        return written.copy(failed = written.failed + 1, failedRootRelPaths = written.failedRootRelPaths + rootRelPath)
    }
}

private fun SqlTestDatabases.livePaths(): List<String> =
    sql.booksQueries
        .selectLiveIdsAndPathsForLibrary(LIBRARY_ID)
        .executeAsList()
        .map { it.root_rel_path }

private fun scanner(
    fixture: AudioLibraryFixture,
    retries: PersistRetryLedger,
): Scanner =
    Scanner(
        library = testLibrary(id = LIBRARY_ID, folders = listOf(fixture.root.toString())),
        metadataReader = AbsMetadataReader(contractJson),
        embeddedMetadataParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList()),
        eventBus = MutableSharedFlow(replay = 64, extraBufferCapacity = 64),
        scanResultBus = MutableSharedFlow<ScanResult>(),
        persistRetries = retries,
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
    ingest: BookIngestPort,
    scope: CoroutineScope,
    retries: PersistRetryLedger,
): BookPersister =
    BookPersister(
        ingest = ingest,
        libraryRegistry = LibraryRegistry(sql),
        libraryRepository = LibraryRepository(sql, ChangeBus(), SyncRegistry()),
        collectionService = inertCollectionService(),
        sql = sql,
        scanResultBus = MutableSharedFlow(),
        eventBus = MutableSharedFlow(),
        changeBus = ChangeBus(),
        scope = scope,
        layoutLock = LibraryLayoutLock(),
        persistRetries = retries,
    )
