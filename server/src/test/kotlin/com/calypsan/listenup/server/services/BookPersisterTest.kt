@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

class BookPersisterTest :
    FunSpec({

        test("persists every AnalyzedBook in ScanResult") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    persister.persist(scanResult(listOf(analyzedBook("a"), analyzedBook("b")), ScanScope.Full))

                    fake.resolved shouldContainExactly listOf("a", "b")
                }
            }
        }

        test("one failing book doesn't kill the rest; metrics counter increments") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest(failForRootRelPath = setOf("b"))
                    val metrics = BookPersisterMetrics(SimpleMeterRegistry())
                    val persister = persister(db, fake, scope = this, metrics = metrics)

                    persister.persist(
                        scanResult(
                            listOf(analyzedBook("a"), analyzedBook("b"), analyzedBook("c")),
                            ScanScope.Full,
                        ),
                    )

                    fake.resolved shouldContainExactly listOf("a", "c")
                    metrics.bookPersistFailures.count() shouldBe 1.0
                }
            }
        }

        test("full scan sweeps absent books") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    persister.persist(scanResult(listOf(analyzedBook("a"), analyzedBook("b")), ScanScope.Full))

                    fake.softDeleteAbsentCalls shouldHaveSize 1
                    fake.softDeleteAbsentCalls.single() shouldBe setOf(BookId("id-a"), BookId("id-b"))
                }
            }
        }

        test("incremental scan does NOT sweep absent books") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    persister.persist(
                        scanResult(listOf(analyzedBook("a")), ScanScope.Subtree("some/path")),
                    )

                    fake.resolved shouldContainExactly listOf("a")
                    fake.softDeleteAbsentCalls.shouldBeEmpty()
                }
            }
        }

        test("an escaped exception is contained; the rest still process") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest(throwForRootRelPath = setOf("b"))
                    val metrics = BookPersisterMetrics(SimpleMeterRegistry())
                    val persister = persister(db, fake, scope = this, metrics = metrics)

                    persister.persist(
                        scanResult(
                            listOf(analyzedBook("a"), analyzedBook("b"), analyzedBook("c")),
                            ScanScope.Full,
                        ),
                    )

                    fake.resolved shouldContainExactly listOf("a", "c")
                    metrics.bookPersistFailures.count() shouldBe 1.0
                }
            }
        }

        test("full scan with a failed book skips the tombstone sweep so a present book is never deleted") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest(failForRootRelPath = setOf("b"))
                    val persister = persister(db, fake, scope = this)

                    persister.persist(
                        scanResult(listOf(analyzedBook("a"), analyzedBook("b")), ScanScope.Full),
                    )

                    // "b" failed to persist, so the seenIds set is an incomplete view of the
                    // library. Sweeping on it would tombstone "b" even though it is present on
                    // disk — so the sweep must be skipped until a clean Full scan.
                    fake.softDeleteAbsentCalls.shouldBeEmpty()
                }
            }
        }
    })

// --- Fakes ------------------------------------------------------------------

/**
 * In-memory [BookIngestPort] for the persister's orchestration tests. The seam
 * exists precisely so this hand-written fake works — no mocking framework.
 *
 * A book whose `rootRelPath` is in [failForRootRelPath] returns a typed
 * [AppResult.Failure]; one in [throwForRootRelPath] throws — covering both
 * containment branches of `BookPersister.persist`.
 */
private class FakeBookIngest(
    private val failForRootRelPath: Set<String> = emptySet(),
    private val throwForRootRelPath: Set<String> = emptySet(),
) : BookIngestPort {
    /** rootRelPaths successfully resolved, in call order. */
    val resolved = mutableListOf<String>()

    /** seenIds sets passed to each [softDeleteAbsent] call. */
    val softDeleteAbsentCalls = mutableListOf<Set<BookId>>()

    override suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: com.calypsan.listenup.core.FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
    ): AppResult<IngestOutcome> {
        val path = analyzed.candidate.rootRelPath
        if (path in throwForRootRelPath) {
            error("simulated escaped failure for $path")
        }
        if (path in failForRootRelPath) {
            return AppResult.Failure(SyncError.NotFound(domain = "books", entityId = path))
        }
        resolved += path
        return AppResult.Success(IngestOutcome(BookId("id-$path"), wasNew = true))
    }

    override suspend fun softDeleteAbsent(
        libraryId: LibraryId,
        seenIds: Set<BookId>,
    ) {
        softDeleteAbsentCalls += seenIds
    }
}

// --- Fixtures ---------------------------------------------------------------

private fun persister(
    db: Database,
    ingest: BookIngestPort,
    scope: CoroutineScope,
    metrics: BookPersisterMetrics = BookPersisterMetrics(SimpleMeterRegistry()),
): BookPersister =
    BookPersister(
        ingest = ingest,
        libraryRegistry = LibraryRegistry(db, env = mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
        db = db,
        scanResultBus = MutableSharedFlow(),
        scope = scope,
        metrics = metrics,
    )

private fun scanResult(
    books: List<AnalyzedBook>,
    scope: ScanScope,
): ScanResult =
    ScanResult(
        correlationId = "c",
        rootPath = "/lib",
        books = books,
        changes = emptyList(),
        errors = emptyList(),
        durationMs = 0L,
        filesWalked = 0,
        filesSkipped = 0,
        scope = scope,
    )

/** Builds a minimal [AnalyzedBook] anchored at [rootRelPath] with one audio file. */
private fun analyzedBook(rootRelPath: String): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    val candidate =
        CandidateBook(
            rootRelPath = rootRelPath,
            isFile = false,
            files = listOf(file),
        )
    return AnalyzedBook(
        candidate = candidate,
        title = rootRelPath,
        tracks = listOf(TrackEntry(file = file)),
    )
}
