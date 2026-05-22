package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException

class BookSyncDomainHandlerTest :
    FunSpec({

        test("Updated event replaces all child rows wholesale") {
            withTestHandler { handler, db ->
                val initial = bookPayload(id = "b1", chapters = (1..10).map { chapter(it) })
                handler
                    .onEvent(created(initial), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.chapterDao().getChaptersForBook(BookId("b1")).size shouldBe 10

                val updated = initial.copy(revision = 2, chapters = (1..3).map { chapter(it) })
                handler
                    .onEvent(updatedEvent(updated), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.chapterDao().getChaptersForBook(BookId("b1")).size shouldBe 3
            }
        }

        test("isOwnEcho updates only revision and updatedAt, leaves child rows untouched") {
            withTestHandler { handler, db ->
                val echoedUpdatedAt = 200L
                val initialChapters = (1..5).map { chapter(it) }
                val initial =
                    bookPayload(
                        id = "b1",
                        title = "Way of Kings",
                        revision = 1,
                        updatedAt = 100L,
                        chapters = initialChapters,
                    )
                handler.onEvent(created(initial), isOwnEcho = false)

                val echoed =
                    initial.copy(
                        title = "Mutated Title",
                        revision = 2,
                        updatedAt = echoedUpdatedAt,
                        chapters = listOf(chapter(99)), // different child list
                    )
                handler.onEvent(updatedEvent(echoed), isOwnEcho = true)

                val row = db.bookDao().getById(BookId("b1"))
                row shouldNotBe null
                row!!.title shouldBe "Way of Kings"
                row.revision shouldBe 2L
                row.updatedAt shouldBe Timestamp(echoedUpdatedAt)

                // Child rows must not have been replaced — echo fast-path skips child writes.
                val chapters = db.chapterDao().getChaptersForBook(BookId("b1"))
                chapters.size shouldBe 5
                chapters.map { it.id.value }.sorted() shouldBe initialChapters.map { it.id }.sorted()
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
                handler.onEvent(created(payload), isOwnEcho = false)

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
                handler.onEvent(created(payload), isOwnEcho = false)

                val stub = db.contributorDao().getById("c9")
                stub shouldNotBe null
                stub!!.name shouldBe "Newly Seen Author"
                stub.revision shouldBe 0L // sentinel — real revision arrives via the contributors domain
            }
        }

        test("onCatchUpItem with isTombstone soft-deletes the book") {
            withTestHandler { handler, db ->
                val initial = bookPayload(id = "b1")
                handler.onEvent(created(initial), isOwnEcho = false)

                handler
                    .onCatchUpItem(initial.copy(deletedAt = 100L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.bookDao().getById(BookId("b1"))?.deletedAt shouldBe 100L
            }
        }

        test("handler self-registers under domainName 'books'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = BookSyncDomainHandler(db, BookEntityMapper(), RoomTransactionRunner(db), registry)
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
                        BookSyncDomainHandler(
                            db,
                            BookEntityMapper(),
                            throwingRunner,
                            ClientSyncDomainRegistry(),
                        )

                    val result = handler.onEvent(created(bookPayload(id = "b1")), isOwnEcho = false)

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
                        BookSyncDomainHandler(
                            db,
                            BookEntityMapper(),
                            cancellingRunner,
                            ClientSyncDomainRegistry(),
                        )

                    var threw: Throwable? = null
                    try {
                        handler.onEvent(created(bookPayload(id = "b1")), isOwnEcho = false)
                    } catch (e: CancellationException) {
                        threw = e
                    }

                    threw.shouldBeInstanceOf<CancellationException>()
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
private fun withTestHandler(block: suspend (BookSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            val handler =
                BookSyncDomainHandler(
                    db,
                    BookEntityMapper(),
                    RoomTransactionRunner(db),
                    ClientSyncDomainRegistry(),
                )
            block(handler, db)
        } finally {
            db.close()
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

private fun chapter(index: Int): BookChapterPayload =
    BookChapterPayload(
        id = "ch$index",
        title = "Chapter $index",
        duration = 60_000L,
        startTime = (index - 1) * 60_000L,
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
): BookSyncPayload =
    BookSyncPayload(
        id = id,
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
        revision = revision,
        updatedAt = updatedAt,
        createdAt = 1L,
        deletedAt = null,
    )
