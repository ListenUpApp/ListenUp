@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionShareRepository
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

class BookPersisterTest :
    FunSpec({

        test("persists changed books from ScanResult.changes") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), analyzedBook("b")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Added(analyzedBook("b")),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    fake.resolved shouldContainExactly listOf("a", "b")
                }
            }
        }

        test("unchanged books in ScanResult.books but absent from changes are not persisted") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    // "a" is unchanged (in books but not in changes); "b" is new (Added).
                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), analyzedBook("b")),
                            changes = listOf(ChangeEventDto.Added(analyzedBook("b"))),
                            scope = ScanScope.Full,
                        ),
                    )

                    // Only "b" went through resolveOrInsert — "a" was skipped entirely.
                    fake.resolved shouldContainExactly listOf("b")
                    // The full-scan sweep still runs with both paths so "a" is not tombstoned.
                    fake.softDeleteAbsentByPathsCalls shouldHaveSize 1
                    fake.softDeleteAbsentByPathsCalls.single() shouldBe setOf("a", "b")
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
                            books = listOf(analyzedBook("a"), analyzedBook("b"), analyzedBook("c")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Added(analyzedBook("b")),
                                    ChangeEventDto.Added(analyzedBook("c")),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    fake.resolved shouldContainExactly listOf("a", "c")
                    metrics.bookPersistFailures.count() shouldBe 1.0
                }
            }
        }

        test("full scan sweeps absent books via seen paths") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), analyzedBook("b")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Added(analyzedBook("b")),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    // The sweep uses seenPaths (rootRelPaths from result.books), not BookIds.
                    fake.softDeleteAbsentByPathsCalls shouldHaveSize 1
                    fake.softDeleteAbsentByPathsCalls.single() shouldBe setOf("a", "b")
                }
            }
        }

        test("emits ScanEvent.Completed only after every changed book is persisted") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 16)
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this, eventBus = eventBus)

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), analyzedBook("b")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Added(analyzedBook("b")),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    // Completed is emitted by the persister — proving it fires AFTER persistence, not
                    // before it (the premature-Completed race). Its summary reflects the total books.
                    val completed = eventBus.replayCache.filterIsInstance<ScanEvent.Completed>().single()
                    completed.result.totalBooks shouldBe 2
                    fake.resolved shouldContainExactly listOf("a", "b")
                }
            }
        }

        test("full scan suppresses the firehose while persisting books") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), analyzedBook("b")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Added(analyzedBook("b")),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    // Every book is persisted with FirehoseSuppressed active, so the bulk burst
                    // never hits the lossy live tail; the sweep runs suppressed too.
                    fake.suppressionObserved shouldContainExactly listOf(true, true)
                    fake.softDeleteAbsentByPathsSuppressed shouldContainExactly listOf(true)
                }
            }
        }

        test("incremental scan persists with the firehose live (no suppression)") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a")),
                            changes = listOf(ChangeEventDto.Modified(analyzedBook("a"), previousRootRelPath = "a")),
                            scope = ScanScope.Subtree("some/path"),
                        ),
                    )

                    // Incremental scans ARE live deltas — they publish normally.
                    fake.suppressionObserved shouldContainExactly listOf(false)
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
                        scanResult(
                            books = listOf(analyzedBook("a")),
                            changes = listOf(ChangeEventDto.Modified(analyzedBook("a"), previousRootRelPath = "a")),
                            scope = ScanScope.Subtree("some/path"),
                        ),
                    )

                    fake.resolved shouldContainExactly listOf("a")
                    fake.softDeleteAbsentByPathsCalls.shouldBeEmpty()
                }
            }
        }

        test("incremental Removed change tombstones the book immediately (no full-scan sweep needed)") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    // Incremental scan: bookRoot subtree had "deleted-book" previously,
                    // now it is gone — the Differ emits Removed. The full-scan sweep does NOT run.
                    persister.persist(
                        scanResult(
                            books = emptyList(), // nothing on disk under the subtree anymore
                            changes = listOf(ChangeEventDto.Removed(rootRelPath = "deleted-book")),
                            scope = ScanScope.Subtree("deleted-book"),
                        ),
                    )

                    // softDeleteByPath must be called with the removed path.
                    fake.softDeleteByPathCalls shouldContainExactly listOf("deleted-book")
                    // No full-scan sweep runs for an incremental scan.
                    fake.softDeleteAbsentByPathsCalls.shouldBeEmpty()
                }
            }
        }

        test("full-scan Removed change also tombstones explicitly (harmless overlap with sweep)") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(db, fake, scope = this)

                    // Full scan where one book was removed: the Differ emits Removed and the sweep runs.
                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Removed(rootRelPath = "gone-book"),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    // Explicit tombstone fires for the Removed change.
                    fake.softDeleteByPathCalls shouldContainExactly listOf("gone-book")
                    // Full-scan sweep also runs (with only the surviving paths).
                    fake.softDeleteAbsentByPathsCalls shouldHaveSize 1
                    fake.softDeleteAbsentByPathsCalls.single() shouldBe setOf("a")
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
                            books = listOf(analyzedBook("a"), analyzedBook("b"), analyzedBook("c")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Added(analyzedBook("b")),
                                    ChangeEventDto.Added(analyzedBook("c")),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    fake.resolved shouldContainExactly listOf("a", "c")
                    metrics.bookPersistFailures.count() shouldBe 1.0
                }
            }
        }

        test("full scan with a failed book still sweeps using seenPaths so the present-but-failed book is protected") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val fake = FakeBookIngest(failForRootRelPath = setOf("b"))
                    val persister = persister(db, fake, scope = this)

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), analyzedBook("b")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Added(analyzedBook("b")),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    // With the path-based sweep, "b" is still in seenPaths (it is on disk even
                    // though its persist call failed), so the sweep runs safely and will NOT
                    // tombstone "b". The guard is seenPaths, not seenIds — no skip needed.
                    fake.softDeleteAbsentByPathsCalls shouldHaveSize 1
                    fake.softDeleteAbsentByPathsCalls.single() shouldBe setOf("a", "b")
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

    /** seenPaths sets passed to each [softDeleteAbsentByPaths] call. */
    val softDeleteAbsentByPathsCalls = mutableListOf<Set<String>>()

    /** rootRelPaths passed to each [softDeleteByPath] call, in call order. */
    val softDeleteByPathCalls = mutableListOf<String>()

    /** Whether [FirehoseSuppressed] was in the coroutine context for each [resolveOrInsert], in call order. */
    val suppressionObserved = mutableListOf<Boolean>()

    /** Whether [FirehoseSuppressed] was in the coroutine context for each [softDeleteAbsentByPaths], in call order. */
    val softDeleteAbsentByPathsSuppressed = mutableListOf<Boolean>()

    override suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: com.calypsan.listenup.core.FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
        inboxCollectionId: String?,
    ): AppResult<IngestOutcome> {
        suppressionObserved += currentCoroutineContext()[FirehoseSuppressed.Key] != null
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

    override suspend fun softDeleteAbsentByPaths(
        libraryId: LibraryId,
        seenPaths: Set<String>,
    ) {
        softDeleteAbsentByPathsSuppressed += currentCoroutineContext()[FirehoseSuppressed.Key] != null
        softDeleteAbsentByPathsCalls += seenPaths
    }

    override suspend fun softDeleteByPath(
        libraryId: LibraryId,
        rootRelPath: String,
    ) {
        softDeleteByPathCalls += rootRelPath
    }
}

// --- Fixtures ---------------------------------------------------------------

private fun persister(
    db: Database,
    ingest: BookIngestPort,
    scope: CoroutineScope,
    eventBus: MutableSharedFlow<ScanEvent> = MutableSharedFlow(),
    metrics: BookPersisterMetrics = BookPersisterMetrics(SimpleMeterRegistry()),
): BookPersister =
    BookPersister(
        ingest = ingest,
        libraryRegistry = LibraryRegistry(db),
        libraryRepository = LibraryRepository(db, ChangeBus(), SyncRegistry()),
        collectionService = inertCollectionService(db),
        db = db,
        scanResultBus = MutableSharedFlow(),
        eventBus = eventBus,
        scope = scope,
        metrics = metrics,
    )

/**
 * A real [CollectionServiceImpl] over [db]. These orchestration tests never enable a
 * library's inbox, so the persister never calls it — it satisfies the constructor only.
 */
private fun inertCollectionService(db: Database): CollectionServiceImpl {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry)
    val shareRepo = CollectionShareRepository(db = db, bus = bus, registry = registry)
    return CollectionServiceImpl(
        collectionRepo = collectionRepo,
        collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry),
        shareRepo = shareRepo,
        accessPolicy = CollectionAccessPolicy(collectionRepo, shareRepo),
        permissionPolicy = UserPermissionPolicy(db),
        bus = bus,
        db = db,
        principal = PrincipalProvider { null },
    )
}

private fun scanResult(
    books: List<AnalyzedBook>,
    changes: List<ChangeEventDto>,
    scope: ScanScope,
): ScanResult =
    ScanResult(
        correlationId = "c",
        rootPath = "/lib",
        books = books,
        changes = changes,
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
