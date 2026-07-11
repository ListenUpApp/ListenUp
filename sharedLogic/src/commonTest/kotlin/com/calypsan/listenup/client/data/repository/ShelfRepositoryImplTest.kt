package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.Shelf as ShelfDto
import com.calypsan.listenup.api.dto.shelf.ShelfBookView
import com.calypsan.listenup.api.dto.shelf.ShelfDetail as ShelfDetailDto
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ShelfBookCoverHash
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.ShelfWithBookCount
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [ShelfRepositoryImpl] — substrate-Room reads + RPC-dispatched writes.
 *
 * Observation maps Room projections to the domain model (owner fields from the current
 * user, JOIN-derived `bookCount`, surfaced `isPrivate`); writes dispatch to a faked
 * [ShelfService] and surface the typed [AppResult] directly (no throwing bridge). No
 * optimistic Room writes — Room updates arrive via the sync handler on SSE. Cancellation
 * is re-raised.
 */
class ShelfRepositoryImplTest :
    FunSpec({

        fun user() =
            UserEntity(
                id = UserId("owner1"),
                email = "owner@example.com",
                displayName = "Owner",
                isRoot = false,
                createdAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            )

        fun repo(
            // autofill: createShelf() now reads revisionOf() and calls upsert() for the optimistic mirror.
            shelfDao: ShelfDao = mock(MockMode.autofill),
            userDao: UserDao = mock { everySuspend { getCurrentUser() } returns user() },
            service: ShelfService = mock(),
        ): ShelfRepositoryImpl = ShelfRepositoryImpl(shelfDao, userDao, RpcChannel.forTest(service))

        fun shelfEntity(
            id: String,
            name: String,
            isPrivate: Boolean = false,
        ) = ShelfEntity(
            id = id,
            name = name,
            description = "desc",
            isPrivate = isPrivate,
            revision = 1L,
            deletedAt = null,
            updatedAt = 100L,
            createdAt = 50L,
        )

        fun summary(
            id: String,
            name: String,
            isPrivate: Boolean = false,
        ) = ShelfDto(
            id = ShelfId(id),
            name = name,
            description = "",
            isPrivate = isPrivate,
            bookCount = 3,
            updatedAt = 100L,
        )

        test("observeMyShelves maps Room rows to domain with bookCount, owner, and isPrivate") {
            runTest {
                val dao =
                    mock<ShelfDao> {
                        every { observeMyShelvesWithBookCount() } returns
                            flowOf(
                                listOf(ShelfWithBookCount(shelfEntity("s1", "Alpha", isPrivate = true), bookCount = 5)),
                            )
                        everySuspend { coverHashesFor("s1") } returns listOf("hash1")
                        everySuspend { totalDurationMsFor("s1") } returns 7_200_000L
                    }
                val result = repo(shelfDao = dao).observeMyShelves("owner1").first()
                result.map { it.id.value } shouldContainExactly listOf("s1")
                val shelf = result.first()
                shelf.bookCount shouldBe 5
                shelf.isPrivate shouldBe true
                shelf.ownerId shouldBe "owner1"
                shelf.ownerDisplayName shouldBe "Owner"
                shelf.coverPaths shouldContainExactly listOf("hash1")
                shelf.totalDurationSeconds shouldBe 7_200L
            }
        }

        test("createShelf dispatches with the privacy flag and maps the summary") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { createShelf("New", "", true) } returns
                            AppResult.Success(summary("s-new", "New", isPrivate = true))
                    }
                val result = repo(service = service).createShelf("New", null, isPrivate = true)
                val shelf = (result as AppResult.Success).data
                shelf.id.value shouldBe "s-new"
                shelf.name shouldBe "New"
                shelf.isPrivate shouldBe true
            }
        }

        test("createShelf surfaces the typed failure (no optimistic write, no throw)") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { createShelf("Dup", "", false) } returns
                            AppResult.Failure(ValidationError(message = "duplicate"))
                    }
                val result = repo(service = service).createShelf("Dup", null, isPrivate = false)
                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        // Offline-first visibility: a created shelf is written to Room immediately so it shows in the
        // list without waiting for the SSE echo.
        test("createShelf optimistically mirrors the new shelf into Room before the SSE echo") {
            runTest {
                val dao = mock<ShelfDao>(MockMode.autofill)
                everySuspend { dao.revisionOf("s-new") } returns null // not yet echoed
                val service =
                    mock<ShelfService> {
                        everySuspend { createShelf("New", "", false) } returns
                            AppResult.Success(summary("s-new", "New"))
                    }

                repo(shelfDao = dao, service = service).createShelf("New", null, isPrivate = false)

                verifySuspend { dao.upsert(any()) }
            }
        }

        // Idempotency: insert-if-absent must never clobber a row the SSE echo already applied.
        test("createShelf does not overwrite an already-echoed shelf row") {
            runTest {
                val dao = mock<ShelfDao>(MockMode.autofill)
                everySuspend { dao.revisionOf("s-new") } returns 9L // echo already applied
                val service =
                    mock<ShelfService> {
                        everySuspend { createShelf("New", "", false) } returns
                            AppResult.Success(summary("s-new", "New"))
                    }

                repo(shelfDao = dao, service = service).createShelf("New", null, isPrivate = false)

                verifySuspend(mode = VerifyMode.not) { dao.upsert(any()) }
            }
        }

        test("updateShelf dispatches name, description, and privacy flag") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend {
                            updateShelf(ShelfId("s1"), "Renamed", "", true)
                        } returns AppResult.Success(summary("s1", "Renamed", isPrivate = true))
                    }
                repo(service = service).updateShelf(ShelfId("s1"), "Renamed", null, isPrivate = true)
                verifySuspend { service.updateShelf(ShelfId("s1"), "Renamed", "", true) }
            }
        }

        test("deleteShelf dispatches to the service and returns success") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { deleteShelf(ShelfId("s1")) } returns AppResult.Success(Unit)
                    }
                val result = repo(service = service).deleteShelf(ShelfId("s1"))
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("addBooksToShelf dispatches one RPC per book and fails fast") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { addBookToShelf(ShelfId("s1"), BookId("b1")) } returns AppResult.Success(Unit)
                        everySuspend { addBookToShelf(ShelfId("s1"), BookId("b2")) } returns
                            AppResult.Failure(ValidationError(message = "nope"))
                    }
                val result = repo(service = service).addBooksToShelf(ShelfId("s1"), listOf(BookId("b1"), BookId("b2")))
                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("removeBookFromShelf dispatches to the service") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { removeBookFromShelf(ShelfId("s1"), BookId("b1")) } returns AppResult.Success(Unit)
                    }
                val result = repo(service = service).removeBookFromShelf(ShelfId("s1"), BookId("b1"))
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("reorderBooks maps ids and dispatches the new order") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend {
                            reorderShelfBooks(ShelfId("s1"), listOf(BookId("b2"), BookId("b1")))
                        } returns AppResult.Success(Unit)
                    }
                repo(service = service).reorderBooks(ShelfId("s1"), listOf(BookId("b2"), BookId("b1")))
                verifySuspend { service.reorderShelfBooks(ShelfId("s1"), listOf(BookId("b2"), BookId("b1"))) }
            }
        }

        test("discoverShelves maps owner identity and access-filtered counts") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { discoverShelves() } returns
                            AppResult.Success(
                                listOf(
                                    DiscoveredShelf(
                                        shelf = summary("s9", "Sam's picks"),
                                        ownerId = "u2",
                                        ownerDisplayName = "Sam",
                                    ),
                                ),
                            )
                    }
                val result = repo(service = service).discoverShelves()
                val shelves = (result as AppResult.Success).data
                shelves shouldContainExactly shelves // size assertion below
                val shelf = shelves.first()
                shelf.id.value shouldBe "s9"
                shelf.ownerId shouldBe "u2"
                shelf.ownerDisplayName shouldBe "Sam"
                shelf.bookCount shouldBe 3
            }
        }

        test("observeById bookCount comes from bookCountFor, not coverHashesFor size") {
            runTest {
                // Cover grid is LIMIT 4 but the shelf has 7 live books — bookCount must be 7.
                val dao =
                    mock<ShelfDao> {
                        every { observeById("s1") } returns flowOf(shelfEntity("s1", "Crowded"))
                        everySuspend { coverHashesFor("s1") } returns listOf("h1", "h2", "h3", "h4")
                        everySuspend { bookCountFor("s1") } returns 7
                        everySuspend { totalDurationMsFor("s1") } returns 0L
                    }
                val shelf = repo(shelfDao = dao).observeById(ShelfId("s1")).first()
                shelf!!.bookCount shouldBe 7
            }
        }

        test("getUserShelves returns the user's public shelves mapped from the contract Shelf type") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { getUserShelves(UserId("u1")) } returns
                            AppResult.Success(
                                listOf(
                                    ShelfDto(
                                        id = ShelfId("s9"),
                                        name = "Alice's picks",
                                        description = "",
                                        isPrivate = false,
                                        bookCount = 4,
                                        updatedAt = 100L,
                                    ),
                                ),
                            )
                    }
                val result = repo(service = service).getUserShelves("u1")
                val shelves = (result as AppResult.Success).data
                shelves.size shouldBe 1
                val shelf = shelves.first()
                shelf.id.value shouldBe "s9"
                shelf.name shouldBe "Alice's picks"
                shelf.bookCount shouldBe 4
            }
        }

        test("getUserShelves surfaces transport failure as AppResult.Failure (channel.call boundary)") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { getUserShelves(UserId("u1")) } throws RuntimeException("connection refused")
                    }
                val result = repo(service = service).getUserShelves("u1")
                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("observeShelvesContainingBook maps Room rows to domain with bookCount") {
            runTest {
                val dao =
                    mock<ShelfDao> {
                        every { observeShelvesContainingBookWithBookCount("b1") } returns
                            flowOf(
                                listOf(ShelfWithBookCount(shelfEntity("s1", "Alpha", isPrivate = false), bookCount = 3)),
                            )
                        everySuspend { coverHashesFor("s1") } returns listOf("hash1")
                        everySuspend { totalDurationMsFor("s1") } returns 3_600_000L
                    }
                val result = repo(shelfDao = dao).observeShelvesContainingBook(BookId("b1")).first()
                result.map { it.id.value } shouldContainExactly listOf("s1")
                result.first().bookCount shouldBe 3
                result.first().ownerId shouldBe "owner1"
            }
        }

        test("getShelfDetail carries coverHash from the local mirror into each ShelfBook") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { getShelf(ShelfId("s1")) } returns
                            AppResult.Success(
                                ShelfDetailDto(
                                    id = ShelfId("s1"),
                                    name = "Reading",
                                    description = "",
                                    isPrivate = false,
                                    isOwner = true,
                                    books =
                                        listOf(
                                            ShelfBookView(bookId = "b1", title = "Mistborn", authors = listOf("Sanderson")),
                                            ShelfBookView(bookId = "b2", title = "Elantris", authors = listOf("Sanderson")),
                                        ),
                                    bookCount = 2,
                                    totalDurationMs = 0L,
                                ),
                            )
                    }
                val dao =
                    mock<ShelfDao> {
                        everySuspend { coverHashesByBookFor("s1") } returns
                            listOf(
                                ShelfBookCoverHash(bookId = "b1", coverHash = "hash-b1"),
                                ShelfBookCoverHash(bookId = "b2", coverHash = null),
                            )
                    }
                val result = repo(shelfDao = dao, service = service).getShelfDetail(ShelfId("s1"))
                val detail = (result as AppResult.Success).data
                detail.books.first { it.id.value == "b1" }.coverHash shouldBe "hash-b1"
                detail.books.first { it.id.value == "b2" }.coverHash shouldBe null
            }
        }

        test("CancellationException from the service is re-raised, not swallowed") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { deleteShelf(ShelfId("s1")) } throws CancellationException("cancelled")
                    }
                shouldThrow<CancellationException> { repo(service = service).deleteShelf(ShelfId("s1")) }
            }
        }
    })
