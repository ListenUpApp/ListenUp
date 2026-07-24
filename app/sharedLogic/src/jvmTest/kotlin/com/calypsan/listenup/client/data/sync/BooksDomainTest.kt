package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookDocumentPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.BookReadershipEntity
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.data.sync.domains.NoOutboxInFlight
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException

class BooksDomainTest :
    FunSpec({

        test("Updated event replaces all child rows wholesale") {
            withTestHandler { handler, db ->
                val initial = bookPayload(id = "b1", chapters = (1..10).map { chapter(it) })
                handler
                    .onEvent(created(initial))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.chapterDao().getChaptersForBook(BookId("b1")).size shouldBe 10

                val updated = initial.copy(revision = 2, chapters = (1..3).map { chapter(it) })
                handler
                    .onEvent(updatedEvent(updated))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.chapterDao().getChaptersForBook(BookId("b1")).size shouldBe 3
            }
        }

        test("server-set fieldProvenance round-trips into the book row") {
            withTestHandler { handler, db ->
                val provenance =
                    mapOf(
                        BookField.TITLE to FieldProvenance(FieldSourceKind.USER, at = 1L),
                        BookField.AUTHORS to FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audible", at = 2L),
                    )
                val payload = bookPayload(id = "b1").copy(fieldProvenance = provenance)
                handler
                    .onEvent(created(payload))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.bookDao().getById(BookId("b1"))?.fieldProvenance shouldBe provenance
            }
        }

        test("a scanner payload with no provenance persists an empty fieldProvenance map") {
            withTestHandler { handler, db ->
                handler
                    .onEvent(created(bookPayload(id = "b1")))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.bookDao().getById(BookId("b1"))?.fieldProvenance shouldBe emptyMap()
            }
        }

        test("Created event persists the audio-stream fields from the payload") {
            withTestHandler { handler, db ->
                val payload =
                    bookPayload(
                        id = "b1",
                        audioFiles =
                            listOf(
                                BookAudioFilePayload(
                                    id = "af1",
                                    index = 0,
                                    filename = "01.m4b",
                                    format = "m4b",
                                    codec = "ac4",
                                    duration = 100L,
                                    size = 1024L,
                                    codecProfile = null,
                                    spatial = "atmos",
                                    bitrate = 320_000,
                                    sampleRate = 48_000,
                                    channels = 2,
                                ),
                            ),
                    )
                handler
                    .onEvent(created(payload))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row = db.audioFileDao().getForBook("b1").single()
                row.spatial shouldBe "atmos"
                row.bitrate shouldBe 320_000
                row.sampleRate shouldBe 48_000
                row.channels shouldBe 2
                row.codecProfile shouldBe null
            }
        }

        test("Updated event replaces book document rows wholesale, ordered by index") {
            withTestHandler { handler, db ->
                val initial = bookPayload(id = "b1", documents = (1..4).map { document(it) })
                handler
                    .onEvent(created(initial))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.bookDocumentDao().getForBook("b1").size shouldBe 4

                val updated = initial.copy(revision = 2, documents = (1..2).map { document(it) })
                handler
                    .onEvent(updatedEvent(updated))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val docs = db.bookDocumentDao().getForBook("b1")
                docs.map { it.id } shouldBe listOf("doc1", "doc2")
                docs.first().filename shouldBe "doc1.pdf"
                docs.first().format shouldBe "pdf"
                docs.first().hash shouldBe "h1"
            }
        }

        test("rescan that rotates a document id GCs the orphaned cache file for the old id (#699)") {
            withGcHandler { handler, _, storage ->
                handler
                    .onEvent(created(bookPayload(id = "b1", documents = listOf(document(1)))))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Server mints a fresh UUID on rescan: doc1 -> doc2 for the same file.
                handler
                    .onEvent(
                        updatedEvent(bookPayload(id = "b1", revision = 2, documents = listOf(document(2)))),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                // The stranded old-id cache file is collected; the new current row is not.
                storage.deletedKeys shouldBe listOf(Triple("b1", "doc1", "pdf"))
            }
        }

        test("rescan that keeps the same document ids touches no cache files (#699)") {
            withGcHandler { handler, _, storage ->
                handler
                    .onEvent(created(bookPayload(id = "b1", documents = listOf(document(1)))))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                handler
                    .onEvent(
                        updatedEvent(bookPayload(id = "b1", revision = 2, documents = listOf(document(1)))),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                storage.deletedKeys shouldBe emptyList()
            }
        }

        test("an inbound snapshot is shielded while a local edit for the book is in flight, then applies once it drains") {
            // The anti-flicker shield: a still-queued local `books` op means the optimistic edit is
            // in flight, so a (possibly stale) inbound snapshot must NOT clobber the local row. Once
            // the op drains, the shield lifts and the snapshot applies — the book converges.
            var opInFlight = false
            val shield = OutboxInFlightQuery { domain, id -> opInFlight && domain == "books" && id == "b1" }
            withTestHandler(inFlightOutbox = shield) { handler, db ->
                val initialChapters = (1..5).map { chapter(it) }
                val initial =
                    bookPayload(
                        id = "b1",
                        title = "Way of Kings",
                        revision = 1,
                        updatedAt = 100L,
                        chapters = initialChapters,
                    )
                handler.onEvent(created(initial))

                // The user's edit is now in flight.
                opInFlight = true

                // A stale echo arrives while the edit is still queued — it must be shielded, leaving
                // the whole optimistic row (title, revision, child rows) untouched.
                val stale =
                    initial.copy(
                        title = "Stale Server Title",
                        revision = 2,
                        updatedAt = 200L,
                        chapters = listOf(chapter(99)),
                    )
                handler.onEvent(updatedEvent(stale))

                val shielded = db.bookDao().getById(BookId("b1"))
                shielded shouldNotBe null
                shielded!!.title shouldBe "Way of Kings"
                shielded.revision shouldBe 1L
                db.chapterDao().getChaptersForBook(BookId("b1")).size shouldBe 5

                // The op drains: the shield lifts and the next inbound snapshot applies fully.
                opInFlight = false
                handler.onEvent(updatedEvent(stale.copy(revision = 3, title = "Converged Title")))

                val converged = db.bookDao().getById(BookId("b1"))
                converged!!.title shouldBe "Converged Title"
                converged.revision shouldBe 3L
                db.chapterDao().getChaptersForBook(BookId("b1")).size shouldBe 1
            }
        }

        test("book handler does not clobber an existing contributor row") {
            withTestHandler { handler, db ->
                db.contributorDao().upsert(
                    contributorEntity(id = "c1", name = "Canonical Name", description = "A prolific author."),
                )
                val payload =
                    bookPayload(
                        id = "b1",
                        contributors = listOf(contrib(id = "c1", name = "Stale Embedded Name")),
                    )
                handler.onEvent(created(payload))

                // The contributor domain owns this row — the book payload's
                // embedded name must NOT overwrite it.
                val contributor = db.contributorDao().getById("c1")!!
                contributor.name shouldBe "Canonical Name"
                contributor.description shouldBe "A prolific author."
            }
        }

        test("book handler inserts a bootstrap stub for a missing contributor") {
            withTestHandler { handler, db ->
                val payload =
                    bookPayload(
                        id = "b1",
                        contributors = listOf(contrib(id = "c9", name = "Newly Seen Author")),
                    )
                handler.onEvent(created(payload))

                val stub = db.contributorDao().getById("c9")
                stub shouldNotBe null
                stub!!.name shouldBe "Newly Seen Author"
                stub.revision shouldBe 0L // sentinel — real revision arrives via the contributors domain
            }
        }

        test("onCatchUpItem with isTombstone soft-deletes the book") {
            withTestHandler { handler, db ->
                val initial = bookPayload(id = "b1")
                handler.onEvent(created(initial))

                handler
                    .onCatchUpItem(initial.copy(deletedAt = 100L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.bookDao().getById(BookId("b1"))?.deletedAt shouldBe 100L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withTestHandler { handler, db ->
                val initial = bookPayload(id = "b1")
                handler.onEvent(created(initial))
                handler.onEvent(
                    SyncEvent.Deleted(id = "b1", revision = 2L, occurredAt = 200L, clientOpId = null),
                )
                // getAllLive filters tombstones — invisible to reads
                db.bookDao().getAllLive().none { it.id == BookId("b1") } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.bookDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "b1"
            }
        }

        test("book tombstone deletes the book's cached readership rows") {
            withTestHandler { handler, db ->
                handler.onEvent(created(bookPayload(id = "b1")))
                handler.onEvent(created(bookPayload(id = "b2")))
                db.bookReadershipDao().upsertAll(
                    listOf(readershipRow("b1", "u1"), readershipRow("b2", "u1")),
                )

                handler.onEvent(
                    SyncEvent.Deleted(id = "b1", revision = 2L, occurredAt = 200L, clientOpId = null),
                )

                db
                    .bookReadershipDao()
                    .observeForBook("b1")
                    .first()
                    .shouldBeEmpty()
                db.bookReadershipDao().observeForBook("b2").first() shouldHaveSize 1
            }
        }

        test("tombstone sweep also removes orphaned readership rows") {
            withTestHandler { handler, db ->
                handler.onEvent(created(bookPayload(id = "b1")))
                // 'ghost' has no books row at all — a pre-existing orphan the sweep self-heals.
                db.bookReadershipDao().upsertAll(
                    listOf(readershipRow("b1", "u1"), readershipRow("ghost", "u1")),
                )

                handler.onEvent(
                    SyncEvent.Deleted(id = "b1", revision = 2L, occurredAt = 200L, clientOpId = null),
                )

                db
                    .bookReadershipDao()
                    .observeForBook("b1")
                    .first()
                    .shouldBeEmpty()
                db
                    .bookReadershipDao()
                    .observeForBook("ghost")
                    .first()
                    .shouldBeEmpty()
            }
        }

        test("handler self-registers under domainName 'books'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler =
                    booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                        .toHandler(transactionRunner = RoomTransactionRunner(db), registry = registry)
                handler.domainName shouldBe "books"
                registry.lookup("books") shouldBe handler
            } finally {
                db.close()
            }
        }

        test("escaped exception inside atomically maps to AppResult.Failure(SyncFailed)") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val throwingRunner =
                        object : TransactionRunner {
                            override suspend fun <R> atomically(block: suspend () -> R): R {
                                error("simulated storage failure")
                            }
                        }
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                            .toHandler(transactionRunner = throwingRunner, registry = ClientSyncDomainRegistry())

                    val result = handler.onEvent(created(bookPayload(id = "b1")))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SyncError.SyncFailed>()
                } finally {
                    db.close()
                }
            }
        }

        test("CancellationException inside atomically is re-thrown, not swallowed") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val cancellingRunner =
                        object : TransactionRunner {
                            override suspend fun <R> atomically(block: suspend () -> R): R = throw CancellationException("cancelled")
                        }
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                            .toHandler(transactionRunner = cancellingRunner, registry = ClientSyncDomainRegistry())

                    var threw: Throwable? = null
                    try {
                        handler.onEvent(created(bookPayload(id = "b1")))
                    } catch (e: CancellationException) {
                        threw = e
                    }

                    threw.shouldBeInstanceOf<CancellationException>()
                } finally {
                    db.close()
                }
            }
        }

        // The cover-staleness fix: a re-covered book gets a new content hash on the wire. The local
        // cover file is id-named and never otherwise re-downloaded, so it must be dropped here, or the
        // UI keeps rendering the old image even across a restart.
        test("a changed cover hash drops the stale local cover file") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val imageStorage =
                        mock<ImageStorage> { everySuspend { deleteCover(any()) } returns AppResult.Success(Unit) }
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = imageStorage)
                            .toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())

                    handler.onEvent(
                        created(bookPayload(id = "b1").copy(cover = CoverPayload(CoverSource.ENRICHED, "h1"))),
                    )
                    handler.onEvent(
                        updatedEvent(bookPayload(id = "b1").copy(cover = CoverPayload(CoverSource.ENRICHED, "h2"))),
                    )

                    verifySuspend(VerifyMode.exactly(1)) { imageStorage.deleteCover(BookId("b1")) }
                } finally {
                    db.close()
                }
            }
        }

        // A4: the cover-file delete must land POST-COMMIT. If a later child apply throws and the
        // aggregate transaction rolls back, Room keeps the OLD coverHash — deleting the file mid-tx
        // would strand the book with no file and no hash change to re-trigger the download.
        test("a rolled-back cover-hash change does NOT delete the file; a committed one DOES (A4)") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val imageStorage =
                        mock<ImageStorage> { everySuspend { deleteCover(any()) } returns AppResult.Success(Unit) }
                    val real = RoomTransactionRunner(db)
                    var failNext = false
                    // Runs the apply inside a REAL transaction, then throws before commit when armed —
                    // reproducing a child apply that fails after the cover-hash change was detected.
                    val runner =
                        object : TransactionRunner {
                            override suspend fun <R> atomically(block: suspend () -> R): R =
                                real.atomically {
                                    val r = block()
                                    if (failNext) error("simulated child-apply failure — roll back")
                                    r
                                }
                        }
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = imageStorage)
                            .toHandler(transactionRunner = runner, registry = ClientSyncDomainRegistry())

                    // Commit a book with cover h1.
                    handler.onEvent(
                        created(bookPayload(id = "b1").copy(cover = CoverPayload(CoverSource.ENRICHED, "h1"))),
                    )

                    // A cover-hash change (h1 -> h2) whose transaction rolls back.
                    failNext = true
                    handler
                        .onEvent(
                            updatedEvent(bookPayload(id = "b1").copy(cover = CoverPayload(CoverSource.ENRICHED, "h2"))),
                        ).shouldBeInstanceOf<AppResult.Failure>()

                    // Rollback: Room still holds h1, and the file was NOT deleted.
                    db.bookDao().getById(BookId("b1"))?.coverHash shouldBe "h1"
                    verifySuspend(VerifyMode.not) { imageStorage.deleteCover(any()) }

                    // The same change again, now committing: the deferred delete runs post-commit.
                    failNext = false
                    handler
                        .onEvent(
                            updatedEvent(bookPayload(id = "b1").copy(cover = CoverPayload(CoverSource.ENRICHED, "h2"))),
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.bookDao().getById(BookId("b1"))?.coverHash shouldBe "h2"
                    verifySuspend(VerifyMode.exactly(1)) { imageStorage.deleteCover(BookId("b1")) }
                } finally {
                    db.close()
                }
            }
        }

        test("an unchanged cover hash leaves the local cover in place") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val imageStorage =
                        mock<ImageStorage> { everySuspend { deleteCover(any()) } returns AppResult.Success(Unit) }
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = imageStorage)
                            .toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())

                    handler.onEvent(
                        created(bookPayload(id = "b1").copy(cover = CoverPayload(CoverSource.ENRICHED, "h1"))),
                    )
                    // Same cover hash, different title — a real update that must NOT touch the cover file.
                    handler.onEvent(
                        updatedEvent(
                            bookPayload(id = "b1").copy(title = "Renamed", cover = CoverPayload(CoverSource.ENRICHED, "h1")),
                        ),
                    )

                    verifySuspend(VerifyMode.not) { imageStorage.deleteCover(any()) }
                } finally {
                    db.close()
                }
            }
        }
    })

/**
 * Builds an in-memory [ListenUpDatabase], a fresh handler backed by a real
 * [RoomTransactionRunner], runs [block] inside [runTest], and closes the database
 * afterwards. Each invocation is fully isolated.
 */
private fun withTestHandler(
    inFlightOutbox: OutboxInFlightQuery = NoOutboxInFlight,
    block: suspend (SyncDomainHandler<BookSyncPayload>, ListenUpDatabase) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        val handler =
            booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                .toHandler(
                    transactionRunner = RoomTransactionRunner(db),
                    registry = ClientSyncDomainRegistry(),
                    inFlightOutbox = inFlightOutbox,
                )
        block(handler, db)
    } finally {
        db.close()
    }
}

/**
 * Like [withTestHandler] but injects a [RecordingDocumentStorage] so document-cache GC
 * can be asserted. The storage is exposed to the block.
 */
private fun withGcHandler(
    block: suspend (SyncDomainHandler<BookSyncPayload>, ListenUpDatabase, RecordingDocumentStorage) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        val storage = RecordingDocumentStorage()
        val handler =
            booksDomain(
                database = db,
                mapper = BookEntityMapper(),
                imageStorage = stubImageStorage(),
                documentStorage = storage,
            ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())
        block(handler, db, storage)
    } finally {
        db.close()
    }
}

/** In-memory [DocumentStorage] that records every [deleteCached] call as a (bookId, docId, format) triple. */
private class RecordingDocumentStorage : DocumentStorage {
    val deletedKeys: MutableList<Triple<String, String, String>> = mutableListOf()

    override fun pathFor(
        bookId: String,
        docId: String,
        format: String,
    ): String = "$bookId/$docId.$format"

    override fun exists(path: String): Boolean = false

    override suspend fun write(
        path: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun deleteCached(
        bookId: String,
        docId: String,
        format: String,
    ) {
        deletedKeys += Triple(bookId, docId, format)
    }
}

private fun created(payload: BookSyncPayload): SyncEvent.Created<BookSyncPayload> =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )

private fun updatedEvent(payload: BookSyncPayload): SyncEvent.Updated<BookSyncPayload> =
    SyncEvent.Updated(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )

private fun readershipRow(
    bookId: String,
    userId: String,
): BookReadershipEntity =
    BookReadershipEntity(
        bookId = bookId,
        userId = userId,
        displayName = "Reader $userId",
        avatarType = "auto",
        currentProgressPct = null,
        finishesJson = "",
        observedAt = 1L,
    )

private fun chapter(index: Int): BookChapterPayload =
    BookChapterPayload(
        id = "ch$index",
        title = "Chapter $index",
        duration = 60_000L,
        startTime = (index - 1) * 60_000L,
    )

private fun document(index: Int): BookDocumentPayload =
    BookDocumentPayload(
        id = "doc$index",
        index = index - 1,
        filename = "doc$index.pdf",
        format = "pdf",
        size = (index * 100).toLong(),
        hash = "h$index",
    )

private fun contrib(
    id: String,
    name: String,
): BookContributorPayload =
    BookContributorPayload(
        id = id,
        name = name,
        sortName = name,
        role = "author",
        creditedAs = null,
    )

private fun contributorEntity(
    id: String,
    name: String,
    description: String?,
): ContributorEntity =
    ContributorEntity(
        id = ContributorId(id),
        name = name,
        sortName = name,
        description = description,
        imagePath = null,
        createdAt = Timestamp(1L),
        updatedAt = Timestamp(1L),
    )

private fun bookPayload(
    id: String,
    title: String = "Test Book",
    revision: Long = 1L,
    updatedAt: Long = 100L,
    chapters: List<BookChapterPayload> = emptyList(),
    contributors: List<BookContributorPayload> = emptyList(),
    series: List<BookSeriesPayload> = emptyList(),
    audioFiles: List<BookAudioFilePayload> = emptyList(),
    documents: List<BookDocumentPayload> = emptyList(),
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1L,
        contributors = contributors,
        series = series,
        audioFiles = audioFiles,
        chapters = chapters,
        documents = documents,
        revision = revision,
        updatedAt = updatedAt,
        createdAt = 1L,
        deletedAt = null,
    )
