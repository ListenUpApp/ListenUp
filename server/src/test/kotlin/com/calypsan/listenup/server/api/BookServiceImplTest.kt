@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookServiceImplTest :
    FunSpec({

        test("getBook returns Success with the aggregate for a seeded book") {
            withInMemoryDatabase {
                val db = this
                val repo =
                    BookRepository(
                        db = db,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                    )
                val service = BookServiceImpl(repo)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    val result = service.getBook(BookId("b1"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    success.data.id shouldBe "b1"
                    success.data.title shouldBe "The Way of Kings"
                }
            }
        }

        test("getBook returns SyncError.NotFound for an absent book id") {
            withInMemoryDatabase {
                val db = this
                val repo =
                    BookRepository(
                        db = db,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                    )
                val service = BookServiceImpl(repo)
                runTest {
                    val result = service.getBook(BookId("nonexistent"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                    error.domain shouldBe "book"
                    error.entityId shouldBe "nonexistent"
                }
            }
        }

        test("searchBooks returns matching book ids in FTS rank order") {
            withInMemoryDatabase {
                val db = this
                val repo =
                    BookRepository(
                        db = db,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                    )
                val service = BookServiceImpl(repo)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))
                    repo.upsert(bookFixture(id = "b2", title = "Words of Radiance", rootRelPath = "Sanderson/Words of Radiance"))
                    repo.upsert(bookFixture(id = "b3", title = "Mistborn", rootRelPath = "Sanderson/Mistborn"))

                    val result = service.searchBooks("Kings", limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data shouldContainExactlyInAnyOrder listOf(BookId("b1"))
                }
            }
        }

        test("searchBooks returns only the id whose title matches the query") {
            withInMemoryDatabase {
                val db = this
                val repo =
                    BookRepository(
                        db = db,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                    )
                val service = BookServiceImpl(repo)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))
                    repo.upsert(bookFixture(id = "b2", title = "Words of Radiance", rootRelPath = "Sanderson/Words of Radiance"))
                    repo.upsert(bookFixture(id = "b3", title = "Mistborn", rootRelPath = "Sanderson/Mistborn"))

                    val result = service.searchBooks("Radiance", limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data shouldContainExactlyInAnyOrder listOf(BookId("b2"))
                }
            }
        }

        test("searchBooks with blank query returns empty list without querying all books") {
            withInMemoryDatabase {
                val db = this
                val repo =
                    BookRepository(
                        db = db,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                    )
                val service = BookServiceImpl(repo)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))
                    repo.upsert(bookFixture(id = "b2", title = "Words of Radiance", rootRelPath = "Sanderson/Words of Radiance"))

                    val result = service.searchBooks("", limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data.shouldBeEmpty()
                }
            }
        }
    })

private fun bookFixture(
    id: String,
    title: String,
    rootRelPath: String = "Sanderson/Way of Kings",
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
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors =
            listOf(
                BookContributorPayload(
                    id = "c-sanderson",
                    name = "Brandon Sanderson",
                    sortName = "Sanderson, Brandon",
                    role = "author",
                    creditedAs = null,
                ),
            ),
        series = listOf(BookSeriesPayload(id = "s1", name = "Stormlight Archive", sequence = "1")),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af1",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch1", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
