@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.dto.scanner.withoutArtwork
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.instanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

class BookPersisterTest :
    FunSpec({

        test("multi-folder scan persists each book under the folderId of ITS OWN folder") {
            withSqlDatabase {
                runTest {
                    // One library, two folders at distinct roots. Each book carries the root it was
                    // walked from; the persister must resolve folder_id per book from that root — not
                    // one folder for the whole scan (the bug that 404'd every non-primary-folder book).
                    val now = 0L
                    sql.librariesQueries.insert(
                        id = "lib",
                        name = "L",
                        metadata_precedence = "embedded",
                        access_mode = "shared",
                        created_by_user_id = null,
                        created_at = now,
                        revision = 0L,
                        updated_at = now,
                        deleted_at = null,
                        client_op_id = null,
                    )
                    sql.libraryFoldersQueries.insert(
                        id = "folder-a",
                        library_id = "lib",
                        root_path = "/mnt/A",
                        created_at = now,
                        revision = 0L,
                        updated_at = now,
                        deleted_at = null,
                        client_op_id = null,
                    )
                    sql.libraryFoldersQueries.insert(
                        id = "folder-b",
                        library_id = "lib",
                        root_path = "/mnt/B",
                        created_at = now,
                        revision = 0L,
                        updated_at = now,
                        deleted_at = null,
                        client_op_id = null,
                    )

                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

                    val bookA = analyzedBook("Book A").copy(folderRootPath = "/mnt/A")
                    val bookB = analyzedBook("Book B").copy(folderRootPath = "/mnt/B")
                    persister.persist(
                        scanResult(
                            books = listOf(bookA, bookB),
                            changes = listOf(ChangeEventDto.Added(bookA), ChangeEventDto.Added(bookB)),
                            scope = ScanScope.Full,
                        ),
                    )

                    fake.folderIdByPath["Book A"] shouldBe FolderId("folder-a")
                    fake.folderIdByPath["Book B"] shouldBe FolderId("folder-b")
                }
            }
        }

        test("persists changed books from ScanResult.changes") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

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

        test("persists the cover-bearing book from ScanResult.books, not the artwork-stripped change") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

                    // The Scanner puts cover-bearing books in `result.books`, but strips artwork
                    // from the copies it places in `result.changes` (via withoutArtwork, which nulls
                    // Embedded/Spooled covers so the volatile artwork never pollutes change detection).
                    // The persister must source each changed book's cover from `result.books`.
                    val coverBearing =
                        analyzedBook("a").copy(
                            cover = CoverSource.Embedded(EmbeddedArtwork(mime = "image/jpeg", bytes = byteArrayOf(1, 2, 3))),
                        )

                    persister.persist(
                        scanResult(
                            books = listOf(coverBearing),
                            changes = listOf(ChangeEventDto.Added(coverBearing.withoutArtwork())),
                            scope = ScanScope.Full,
                        ),
                    )

                    // resolveOrInsert must see the cover, not the stripped (null-cover) change copy.
                    fake.coverByPath["a"] shouldBe coverBearing.cover
                }
            }
        }

        test("unchanged books in ScanResult.books but absent from changes are not persisted") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

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

        test("resolveScanIdentities is called once before the loop with exactly the changed cover-bearing books") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

                    // "a" unchanged (in books, not in changes); "b" Added; "c" Modified; "d" Removed.
                    val coverBearingB =
                        analyzedBook("b").copy(
                            cover = CoverSource.Embedded(EmbeddedArtwork(mime = "image/jpeg", bytes = byteArrayOf(9))),
                        )
                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), coverBearingB, analyzedBook("c")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(coverBearingB.withoutArtwork()),
                                    ChangeEventDto.Modified(analyzedBook("c"), previousRootRelPath = "c"),
                                    ChangeEventDto.Removed("d"),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    // Batch identity resolution runs exactly once, before any per-book persist.
                    fake.resolveScanIdentitiesCalls shouldHaveSize 1
                    // It covers exactly the to-persist (Added/Modified/Moved) books — not "a" (unchanged)
                    // nor "d" (Removed) — and uses the cover-bearing copies (matching what persist writes).
                    val batchPaths = fake.resolveScanIdentitiesCalls.single().map { it.candidate.rootRelPath }
                    batchPaths shouldContainExactly listOf("b", "c")
                    fake.resolveScanIdentitiesCalls
                        .single()
                        .first { it.candidate.rootRelPath == "b" }
                        .cover shouldBe
                        coverBearingB.cover
                }
            }
        }

        test("one failing book doesn't kill the rest") {
            withSqlDatabase {
                runTest {
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 16)
                    val fake = FakeBookIngest(failForRootRelPath = setOf("b"))
                    val persister = persister(fake, scope = this, eventBus = eventBus)

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
                    val completed = eventBus.replayCache.filterIsInstance<ScanEvent.Completed>().single()
                    completed.result.failed shouldBe 1
                }
            }
        }

        test("full scan sweeps absent books via seen paths") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

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
            withSqlDatabase {
                runTest {
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 16)
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this, eventBus = eventBus)

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

        test("persistAll emits PERSISTING progress with live counts, ending at the total, before Completed") {
            withSqlDatabase {
                runTest {
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 64)
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this, eventBus = eventBus)

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

                    val events = eventBus.replayCache
                    val persistProgress =
                        events.filterIsInstance<ScanEvent.Progress>().filter { it.phase == ScanPhase.PERSISTING }

                    // Persistence is now a visible phase, not a silent gap.
                    persistProgress.isNotEmpty() shouldBe true
                    // Every event is scoped to the 3 books being written.
                    persistProgress.all { it.booksTotal == 3 } shouldBe true
                    // It starts at 0 (so the UI leaves the 100%-analyze state) and reaches the total.
                    persistProgress.first().booksAnalyzed shouldBe 0
                    persistProgress.last().booksAnalyzed shouldBe 3

                    // PERSISTING progress lands before the terminal Completed.
                    val lastPersist = events.indexOfLast { (it as? ScanEvent.Progress)?.phase == ScanPhase.PERSISTING }
                    val completed = events.indexOfFirst { it is ScanEvent.Completed }
                    (lastPersist in 0 until completed) shouldBe true
                }
            }
        }

        test("Completed carries real persisted + failed counts when some books fail") {
            withSqlDatabase {
                runTest {
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 16)
                    // "b" fails via typed AppResult.Failure; "c" fails via thrown exception
                    val fake =
                        FakeBookIngest(
                            failForRootRelPath = setOf("b"),
                            throwForRootRelPath = setOf("c"),
                        )
                    val persister = persister(fake, scope = this, eventBus = eventBus)

                    persister.persist(
                        scanResult(
                            books = listOf(analyzedBook("a"), analyzedBook("b"), analyzedBook("c"), analyzedBook("d")),
                            changes =
                                listOf(
                                    ChangeEventDto.Added(analyzedBook("a")),
                                    ChangeEventDto.Added(analyzedBook("b")),
                                    ChangeEventDto.Added(analyzedBook("c")),
                                    ChangeEventDto.Added(analyzedBook("d")),
                                ),
                            scope = ScanScope.Full,
                        ),
                    )

                    val completed = eventBus.replayCache.filterIsInstance<ScanEvent.Completed>().single()
                    // "a" and "d" persisted; "b" typed-failure, "c" threw — both are failures
                    completed.result.persisted shouldBe 2
                    completed.result.failed shouldBe 2
                }
            }
        }

        test("Completed reports all persisted and zero failed when every book succeeds") {
            withSqlDatabase {
                runTest {
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 16)
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this, eventBus = eventBus)

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

                    val completed = eventBus.replayCache.filterIsInstance<ScanEvent.Completed>().single()
                    completed.result.persisted shouldBe 2
                    completed.result.failed shouldBe 0
                }
            }
        }

        test("OutOfMemoryError stops the loop, emits Completed with partial counts, then rethrows") {
            withSqlDatabase {
                runTest {
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 16)
                    val fake = FakeBookIngest(oomForRootRelPath = setOf("b"))
                    val persister = persister(fake, scope = this, eventBus = eventBus)

                    // OOM rethrows — expect it to propagate
                    val thrown =
                        runCatching {
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
                        }
                    thrown.exceptionOrNull() shouldBe instanceOf(OutOfMemoryError::class)

                    // A Completed event is still emitted before the rethrow so clients get honest counts
                    val completed = eventBus.replayCache.filterIsInstance<ScanEvent.Completed>().single()
                    completed.result.persisted shouldBe 1 // "a" succeeded
                    completed.result.failed shouldBe 1 // "b" OOM'd — "c" never ran

                    // "c" was never reached because the loop stopped on OOM
                    fake.resolved shouldContainExactly listOf("a")
                }
            }
        }

        test("full scan suppresses the firehose while persisting books") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

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
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

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

        test("a large incremental scan suppresses the firehose to avoid a live-tail storm") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

                    // A bulk incremental — dropping a folder of many books, or a large
                    // subtree re-persist — would flood the lossy live tail (ChangeBus
                    // replay=256, DROP_OLDEST) and storm connected clients into a per-event
                    // transaction GC storm. It must suppress like a full scan; the client
                    // reconciles once via catch-up on ScanEvent.Completed. Small incrementals
                    // stay live (covered by the test above).
                    val many = (1..LARGE_INCREMENTAL_COUNT).map { "book-$it" }
                    persister.persist(
                        scanResult(
                            books = many.map { analyzedBook(it) },
                            changes = many.map { ChangeEventDto.Added(analyzedBook(it)) },
                            scope = ScanScope.Subtree("big/folder"),
                        ),
                    )

                    fake.suppressionObserved shouldContainExactly List(LARGE_INCREMENTAL_COUNT) { true }
                }
            }
        }

        test("incremental scan does NOT sweep absent books") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

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
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

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
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

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

        test("incremental Removed resolves the owning folder from the change, not the scan's primary root") {
            withSqlDatabase {
                runTest {
                    // One library, two folders. The scan's primary root is folder A; the removal happened
                    // in folder B. Resolving the removal from the primary root (the bug) would tombstone
                    // the wrong folder — or a same-relpath book in folder A. The Removed carries folder B's
                    // root, so the persister must resolve folder-b for the deletion.
                    val now = 0L
                    sql.librariesQueries.insert(
                        id = "lib",
                        name = "L",
                        metadata_precedence = "embedded",
                        access_mode = "shared",
                        created_by_user_id = null,
                        created_at = now,
                        revision = 0L,
                        updated_at = now,
                        deleted_at = null,
                        client_op_id = null,
                    )
                    sql.libraryFoldersQueries.insert(
                        id = "folder-a",
                        library_id = "lib",
                        root_path = "/mnt/A",
                        created_at = now,
                        revision = 0L,
                        updated_at = now,
                        deleted_at = null,
                        client_op_id = null,
                    )
                    sql.libraryFoldersQueries.insert(
                        id = "folder-b",
                        library_id = "lib",
                        root_path = "/mnt/B",
                        created_at = now,
                        revision = 0L,
                        updated_at = now,
                        deleted_at = null,
                        client_op_id = null,
                    )

                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

                    persister.persist(
                        scanResult(
                            books = emptyList(), // the subtree under the vanished book is empty now
                            changes =
                                listOf(
                                    ChangeEventDto.Removed(rootRelPath = "Shared/Book", folderRootPath = "/mnt/B"),
                                ),
                            scope = ScanScope.Subtree("Shared/Book"),
                            rootPath = "/mnt/A", // primary root is folder A — the OLD buggy resolution target
                        ),
                    )

                    fake.softDeleteByPathCalls shouldContainExactly listOf("Shared/Book")
                    // Resolved to folder B (the vanished book's owning folder), NOT folder A (primary root),
                    // so a same-relpath book in folder A is never tombstoned.
                    fake.softDeleteByPathFolderIds["Shared/Book"] shouldBe FolderId("folder-b")
                }
            }
        }

        test("full scan skips the tombstone sweep when a walked root resolves to the unknown-folder sentinel") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest()
                    val persister = persister(fake, scope = this)

                    // A book whose owning-folder root has NO library_folders row resolves to the "unknown"
                    // sentinel. Sweeping would compare the whole library's live books against a
                    // sentinel-keyed seen set and tombstone them all (the original corruption via the
                    // fallback). The sweep must be skipped entirely; the book is still persisted.
                    val ghost = analyzedBook("Book").copy(folderRootPath = "/mnt/ghost")
                    persister.persist(
                        scanResult(
                            books = listOf(ghost),
                            changes = listOf(ChangeEventDto.Added(ghost)),
                            scope = ScanScope.Full,
                        ),
                    )

                    fake.resolved shouldContainExactly listOf("Book")
                    // No sweep ran — the sentinel guard tripped.
                    fake.softDeleteAbsentByPathsCalls.shouldBeEmpty()
                }
            }
        }

        test("an escaped exception is contained; the rest still process") {
            withSqlDatabase {
                runTest {
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 16)
                    val fake = FakeBookIngest(throwForRootRelPath = setOf("b"))
                    val persister = persister(fake, scope = this, eventBus = eventBus)

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
                    val completed = eventBus.replayCache.filterIsInstance<ScanEvent.Completed>().single()
                    completed.result.failed shouldBe 1
                }
            }
        }

        test("full scan with a failed book still sweeps using seenPaths so the present-but-failed book is protected") {
            withSqlDatabase {
                runTest {
                    val fake = FakeBookIngest(failForRootRelPath = setOf("b"))
                    val persister = persister(fake, scope = this)

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

/** Changed-book count for the large-incremental suppression test — above the production threshold. */
internal const val LARGE_INCREMENTAL_COUNT = 60

// --- Fakes ------------------------------------------------------------------

/**
 * In-memory [BookIngestPort] for the persister's orchestration tests. The seam
 * exists precisely so this hand-written fake works — no mocking framework.
 *
 * A book whose `rootRelPath` is in [failForRootRelPath] returns a typed
 * [AppResult.Failure]; one in [throwForRootRelPath] throws — covering both
 * containment branches of `BookPersister.persist`. One in [oomForRootRelPath]
 * throws [OutOfMemoryError] — covering the must-not-swallow OOM branch.
 */
internal class FakeBookIngest(
    private val failForRootRelPath: Set<String> = emptySet(),
    private val throwForRootRelPath: Set<String> = emptySet(),
    private val oomForRootRelPath: Set<String> = emptySet(),
) : BookIngestPort {
    /** rootRelPaths successfully resolved, in call order. */
    val resolved = mutableListOf<String>()

    /** The [AnalyzedBook.cover] each [resolveOrInsert] saw, keyed by rootRelPath. */
    val coverByPath = mutableMapOf<String, CoverSource?>()

    /** The [com.calypsan.listenup.core.FolderId] each [resolveOrInsert] saw, keyed by rootRelPath. */
    val folderIdByPath = mutableMapOf<String, com.calypsan.listenup.core.FolderId>()

    /** seenPaths sets passed to each [softDeleteAbsentByPaths] call. */
    val softDeleteAbsentByPathsCalls = mutableListOf<Set<String>>()

    /** rootRelPaths passed to each [softDeleteByPath] call, in call order. */
    val softDeleteByPathCalls = mutableListOf<String>()

    /** The [FolderId] each [softDeleteByPath] resolved to, keyed by rootRelPath. */
    val softDeleteByPathFolderIds = mutableMapOf<String, FolderId>()

    /** Whether [FirehoseSuppressed] was in the coroutine context for each [resolveOrInsert], in call order. */
    val suppressionObserved = mutableListOf<Boolean>()

    /** Whether [FirehoseSuppressed] was in the coroutine context for each [softDeleteAbsentByPaths], in call order. */
    val softDeleteAbsentByPathsSuppressed = mutableListOf<Boolean>()

    /** The book collections passed to each [resolveScanIdentities] call, in call order. */
    val resolveScanIdentitiesCalls = mutableListOf<Collection<AnalyzedBook>>()

    override suspend fun resolveScanIdentities(books: Collection<AnalyzedBook>): ScanIdentityMaps {
        resolveScanIdentitiesCalls += books
        return ScanIdentityMaps()
    }

    override suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: com.calypsan.listenup.core.FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
        systemCollectionId: String?,
        contributorIds: Map<String, com.calypsan.listenup.core.ContributorId>?,
        seriesIds: Map<String, com.calypsan.listenup.core.SeriesId>?,
    ): AppResult<IngestOutcome> {
        suppressionObserved += currentCoroutineContext()[FirehoseSuppressed.Key] != null
        val path = analyzed.candidate.rootRelPath
        coverByPath[path] = analyzed.cover
        folderIdByPath[path] = folderId
        if (path in oomForRootRelPath) {
            throw OutOfMemoryError("simulated OOM for $path")
        }
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
        seen: Set<FolderScopedPath>,
    ) {
        softDeleteAbsentByPathsSuppressed += currentCoroutineContext()[FirehoseSuppressed.Key] != null
        // Record just the paths — these orchestration tests assert WHICH paths are swept, not folder
        // attribution (the folder-qualified sweep is covered at the repository level in
        // BookIdentityStabilityTest).
        softDeleteAbsentByPathsCalls += seen.mapTo(mutableSetOf()) { it.rootRelPath }
    }

    override suspend fun softDeleteByPath(
        folderId: FolderId,
        rootRelPath: String,
    ) {
        softDeleteByPathCalls += rootRelPath
        softDeleteByPathFolderIds[rootRelPath] = folderId
    }
}

// --- Fixtures ---------------------------------------------------------------

internal suspend fun SqlTestDatabases.persister(
    ingest: BookIngestPort,
    scope: CoroutineScope,
    eventBus: MutableSharedFlow<ScanEvent> = MutableSharedFlow(),
    changeBus: ChangeBus = ChangeBus(),
): BookPersister {
    // Seed a library_folders row at the default scanResult rootPath ("/lib") so folder resolution
    // finds a REAL folder rather than the "unknown" sentinel — which now (finding 5) skips the
    // full-scan sweep. Tests that deliberately exercise an unresolvable root do not walk "/lib".
    // Idempotent-guarded so the multi-folder test (which seeds its own rows) never double-inserts.
    val libId = LibraryRegistry(sql).currentLibrary()
    if (sql.libraryFoldersQueries.selectLiveByRootPath("/lib").executeAsOneOrNull() == null) {
        sql.libraryFoldersQueries.insert(
            id = "folder-lib-default",
            library_id = libId.value,
            root_path = "/lib",
            created_at = 0L,
            revision = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
        )
    }
    return BookPersister(
        ingest = ingest,
        libraryRegistry = LibraryRegistry(sql),
        libraryRepository = LibraryRepository(sql, ChangeBus(), SyncRegistry()),
        collectionService = inertCollectionService(),
        sql = sql,
        scanResultBus = MutableSharedFlow(),
        eventBus = eventBus,
        changeBus = changeBus,
        scope = scope,
    )
}

/**
 * A real [CollectionServiceImpl] over [sql]. These orchestration tests never enable a
 * library's inbox, so the persister never calls it — it satisfies the constructor only.
 */
internal fun SqlTestDatabases.inertCollectionService(): CollectionServiceImpl {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver)
    val grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver)
    return CollectionServiceImpl(
        collectionRepo = collectionRepo,
        collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
        grantRepo = grantRepo,
        accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo),
        permissionPolicy = UserPermissionPolicy(sql),
        bus = bus,
        sql = sql,
        bookRevisionTouch = FakeBookRevisionTouch(),
        principal = PrincipalProvider { null },
    )
}

internal fun scanResult(
    books: List<AnalyzedBook>,
    changes: List<ChangeEventDto>,
    scope: ScanScope,
    rootPath: String = "/lib",
): ScanResult =
    ScanResult(
        correlationId = "c",
        rootPath = rootPath,
        books = books,
        changes = changes,
        errors = emptyList(),
        durationMs = 0L,
        filesWalked = 0,
        filesSkipped = 0,
        scope = scope,
    )

/** Builds a minimal [AnalyzedBook] anchored at [rootRelPath] with one audio file. */
internal fun analyzedBook(rootRelPath: String): AnalyzedBook {
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
