package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperation
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ChapterId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * The offline-first proof for every [BookEditRepository] edit surface: with a sender that never
 * drains, each edit still (a) writes its optimistic Room merge so the UI reflects it immediately, and
 * (b) enqueues exactly one durable `books` op carrying the matching [BookMutation] variant — never a
 * [com.calypsan.listenup.api.error.ServerConnectError]. A final test drains with a recording sender to
 * prove the queued op decodes to the right mutation and is removed.
 */
class BookEditRepositoryOfflineTest :
    FunSpec({

        test("updateBook writes the optimistic title + provenance and enqueues BookMutation.Update") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Old Title")
                    val repo = db.bookEditRepository()

                    val result = repo.updateBook(bookId, BookUpdate(title = "New Title", description = "New Desc"))

                    result shouldBe AppResult.Success(Unit)
                    val book = db.bookDao().getById(bookId)
                    book?.title shouldBe "New Title"
                    book?.description shouldBe "New Desc"
                    book?.userEditedFields shouldBe setOf(UserEditedField.TITLE, UserEditedField.DESCRIPTION)

                    val op = db.singleQueuedBooksOp()
                    val mutation = op.decodeMutation()
                    mutation.shouldBeInstanceOf<BookMutation.Update>()
                    mutation.patch.title shouldBe "New Title"
                } finally {
                    db.close()
                }
            }
        }

        test("setBookContributors links an existing contributor optimistically and enqueues SetContributors") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Book")
                    db.seedContributor(ContributorId("c1"), name = "Ursula")
                    val repo = db.bookEditRepository()

                    val contributors =
                        listOf(BookContributorInput(id = ContributorId("c1"), name = "Ursula", role = "author", position = 0))
                    repo.setBookContributors(bookId, contributors).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Optimistic junction is present immediately (no drain).
                    db.contributorDao().getByBookId(bookId.value).map { it.id.value } shouldContainExactly listOf("c1")

                    db.singleQueuedBooksOp().decodeMutation().shouldBeInstanceOf<BookMutation.SetContributors>()
                } finally {
                    db.close()
                }
            }
        }

        test("setBookSeries links an existing series optimistically and enqueues SetSeries") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Book")
                    db.seedSeries(SeriesId("s1"), name = "Earthsea")
                    val repo = db.bookEditRepository()

                    val series = listOf(BookSeriesInput(id = SeriesId("s1"), name = "Earthsea", position = 1.0))
                    repo.setBookSeries(bookId, series).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db
                        .bookDao()
                        .getByIdWithContributors(bookId)
                        ?.series
                        ?.map { it.id.value } shouldContainExactly
                        listOf("s1")
                    db.singleQueuedBooksOp().decodeMutation().shouldBeInstanceOf<BookMutation.SetSeries>()
                } finally {
                    db.close()
                }
            }
        }

        test("setBookGenres links an existing genre optimistically and enqueues SetGenres") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Book")
                    db.seedGenre("g1", name = "Fantasy")
                    val repo = db.bookEditRepository()

                    repo
                        .setBookGenres(bookId, listOf(BookGenreInput(GenreId("g1"))))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.genreDao().getGenresForBook(bookId).map { it.id } shouldContainExactly listOf("g1")
                    db.singleQueuedBooksOp().decodeMutation().shouldBeInstanceOf<BookMutation.SetGenres>()
                } finally {
                    db.close()
                }
            }
        }

        test("setBookChapters writes chapters optimistically and enqueues SetChapters") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Book")
                    val repo = db.bookEditRepository()

                    val chapters =
                        listOf(
                            ChapterInput(id = "ch1", title = "One", startTime = 0, duration = 1000),
                            ChapterInput(id = "ch2", title = "Two", startTime = 1000, duration = 1000),
                        )
                    repo.setBookChapters(bookId, chapters).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.chapterDao().getChaptersForBook(bookId).map { it.id } shouldContainExactly
                        listOf(ChapterId("ch1"), ChapterId("ch2"))
                    db.singleQueuedBooksOp().decodeMutation().shouldBeInstanceOf<BookMutation.SetChapters>()
                } finally {
                    db.close()
                }
            }
        }

        test("deleteBookCover clears the cover pointer optimistically and enqueues DeleteCover") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Book", coverHash = "hash", coverDownloadedAt = Timestamp(42L))
                    val repo = db.bookEditRepository()

                    repo.deleteBookCover(bookId).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val book = db.bookDao().getById(bookId)
                    book?.coverHash.shouldBeNull()
                    book?.coverDownloadedAt.shouldBeNull()
                    db.singleQueuedBooksOp().decodeMutation() shouldBe BookMutation.DeleteCover
                } finally {
                    db.close()
                }
            }
        }

        test("setBookCollections diffs membership optimistically and enqueues SetCollections") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Book")
                    // Book currently in collection "old"; target set is "new" only.
                    db.seedCollection("old")
                    db.seedCollection("new")
                    db.collectionBookDao().upsert(
                        CollectionBookEntity(collectionId = "old", bookId = bookId.value, createdAt = 1L),
                    )
                    val repo = db.bookEditRepository()

                    repo.setBookCollections(bookId, listOf("new")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    db.collectionBookDao().liveCollectionIdsForBook(bookId.value) shouldContainExactly listOf("new")
                    db.singleQueuedBooksOp().decodeMutation().shouldBeInstanceOf<BookMutation.SetCollections>()
                } finally {
                    db.close()
                }
            }
        }

        test("a stale books echo does NOT revert an optimistic set-op while its op is queued") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Book")
                    db.seedGenre("g1", name = "Fantasy")
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = PendingOperationSender { AppResult.Success(Unit) },
                        )
                    val handler =
                        booksDomain(database = db, mapper = BookEntityMapper(), imageStorage = stubImageStorage())
                            .toHandler(
                                RoomTransactionRunner(db),
                                ClientSyncDomainRegistry(),
                                OutboxInFlightQuery(queue::hasQueuedOpFor),
                            )
                    val repo = db.bookEditRepository(queue)

                    // Optimistic set-op: genre g1 linked, `books` op in flight.
                    repo.setBookGenres(bookId, listOf(BookGenreInput(GenreId("g1"))))
                    db.genreDao().getGenresForBook(bookId).map { it.id } shouldContainExactly listOf("g1")

                    // A stale echo (server's pre-edit state, no genres) MUST be shielded.
                    handler.onEvent(bookUpdatedEvent(bookId.value, revision = 2))
                    db.genreDao().getGenresForBook(bookId).map { it.id } shouldContainExactly listOf("g1")

                    // Once the op drains (removed), the next echo applies and the book converges.
                    db.pendingOperationV2Dao().deleteAll()
                    handler.onEvent(bookUpdatedEvent(bookId.value, revision = 3))
                    db.genreDao().getGenresForBook(bookId).shouldBeEmpty()
                } finally {
                    db.close()
                }
            }
        }

        test("draining a queued set-op decodes to the right mutation and removes the op") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val bookId = BookId("book1")
                    db.seedBook(bookId, title = "Book")
                    db.seedGenre("g1", name = "Fantasy")

                    var dispatched: BookMutation? = null
                    val recordingSender =
                        PendingOperationSender { op: PendingOperation ->
                            dispatched = contractJson.decodeFromString(BookMutation.serializer(), op.payload)
                            AppResult.Success(Unit)
                        }
                    val queue = PendingOperationQueue(dao = db.pendingOperationV2Dao(), sender = recordingSender)
                    val repo = db.bookEditRepository(queue)

                    repo.setBookGenres(bookId, listOf(BookGenreInput(GenreId("g1"))))
                    queue.drain()

                    dispatched.shouldBeInstanceOf<BookMutation.SetGenres>()
                    db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).shouldBeEmpty()
                } finally {
                    db.close()
                }
            }
        }
    })

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun <T> List<T>.shouldBeEmpty() {
    if (isNotEmpty()) throw AssertionError("expected empty list but was $this")
}

private fun ListenUpDatabase.bookEditRepository(
    queue: PendingOperationQueue =
        PendingOperationQueue(
            dao = pendingOperationV2Dao(),
            sender = PendingOperationSender { AppResult.Success(Unit) },
        ),
): BookEditRepository =
    BookEditRepositoryImpl(
        offlineEditor =
            OfflineEditor(
                pendingQueue = queue,
                transactionRunner = RoomTransactionRunner(this),
                authSession = FakeAuthSession(userId = "u1"),
            ),
        localApply =
            BookMutationLocalApply(
                bookDao = bookDao(),
                bookContributorDao = bookContributorDao(),
                contributorDao = contributorDao(),
                bookSeriesDao = bookSeriesDao(),
                seriesDao = seriesDao(),
                genreDao = genreDao(),
                chapterDao = chapterDao(),
                collectionBookDao = collectionBookDao(),
            ),
    )

private suspend fun ListenUpDatabase.singleQueuedBooksOp(): PendingOperationV2Entity {
    val ops = pendingOperationV2Dao().nextDispatchable(maxAttempts = 5)
    ops.size shouldBe 1
    ops.single().domainName shouldBe OutboxChannels.Books.name
    return ops.single()
}

private fun PendingOperationV2Entity.decodeMutation(): BookMutation = contractJson.decodeFromString(BookMutation.serializer(), payload)

private suspend fun ListenUpDatabase.seedBook(
    id: BookId,
    title: String,
    coverHash: String? = null,
    coverDownloadedAt: Timestamp? = null,
) = bookDao().upsert(
    BookEntity(
        id = id,
        libraryId = LibraryId("lib1"),
        folderId = FolderId("folder1"),
        title = title,
        coverHash = coverHash,
        coverDownloadedAt = coverDownloadedAt,
        totalDuration = 3_600_000L,
        createdAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    ),
)

private suspend fun ListenUpDatabase.seedContributor(
    id: ContributorId,
    name: String,
) = contributorDao().upsert(
    ContributorEntity(
        id = id,
        name = name,
        description = null,
        imagePath = null,
        createdAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    ),
)

private suspend fun ListenUpDatabase.seedSeries(
    id: SeriesId,
    name: String,
) = seriesDao().upsert(
    SeriesEntity(
        id = id,
        name = name,
        description = null,
        createdAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    ),
)

private suspend fun ListenUpDatabase.seedGenre(
    id: String,
    name: String,
) = genreDao().upsert(
    GenreEntity(
        id = id,
        name = name,
        slug = name.lowercase(),
        path = "/${name.lowercase()}",
        parentId = null,
        depth = 0,
        sortOrder = 0,
        revision = 0L,
        deletedAt = null,
        createdAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    ),
)

/** A stale `books` SSE echo carrying no genres — applying it would clear the book's genres. */
private fun bookUpdatedEvent(
    id: String,
    revision: Long,
): SyncEvent.Updated<BookSyncPayload> {
    val payload =
        BookSyncPayload(
            id = id,
            libraryId = LibraryId("lib1"),
            folderId = FolderId("folder1"),
            title = "Book",
            sortTitle = "Book",
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
            contributors = emptyList(),
            series = emptyList(),
            audioFiles = emptyList(),
            chapters = emptyList(),
            revision = revision,
            updatedAt = revision * 100L,
            createdAt = 1L,
            deletedAt = null,
        )
    return SyncEvent.Updated(id = id, revision = revision, occurredAt = payload.updatedAt, payload = payload)
}

private suspend fun ListenUpDatabase.seedCollection(id: String) =
    collectionDao().upsert(
        CollectionEntity(
            id = id,
            libraryId = "lib1",
            ownerId = "u1",
            name = "Collection $id",
            isInbox = false,
            updatedAt = 0L,
        ),
    )
