package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.shelf.Shelf as ShelfDto
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.ShelfWithBookCount
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.ShelfRpcFactory
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [ShelfRepositoryImpl] — substrate-Room reads + RPC-dispatched writes.
 *
 * Observation maps Room projections to the domain model (owner fields from the current
 * user, JOIN-derived `bookCount`); writes dispatch to a faked [ShelfService]. No optimistic
 * Room writes — Room updates arrive via the sync handler on SSE. Cancellation is re-raised.
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
                avatarColor = "#112233",
            )

        fun repo(
            shelfDao: ShelfDao = mock(),
            userDao: UserDao = mock { everySuspend { getCurrentUser() } returns user() },
            service: ShelfService = mock(),
        ): ShelfRepositoryImpl {
            val factory: ShelfRpcFactory = mock()
            everySuspend { factory.get() } returns service
            return ShelfRepositoryImpl(shelfDao, userDao, factory)
        }

        fun shelfEntity(
            id: String,
            name: String,
        ) = ShelfEntity(
            id = id,
            name = name,
            description = "desc",
            isPrivate = false,
            revision = 1L,
            deletedAt = null,
            updatedAt = 100L,
            createdAt = 50L,
        )

        fun summary(
            id: String,
            name: String,
        ) = ShelfDto(
            id = ShelfId(id),
            name = name,
            description = "",
            isPrivate = false,
            bookCount = 3,
            updatedAt = 100L,
        )

        test("observeMyShelves maps Room rows to domain with JOIN-derived bookCount and owner") {
            runTest {
                val dao =
                    mock<ShelfDao> {
                        every { observeMyShelvesWithBookCount() } returns
                            flowOf(listOf(ShelfWithBookCount(shelfEntity("s1", "Alpha"), bookCount = 5)))
                        everySuspend { coverHashesFor("s1") } returns listOf("hash1")
                        everySuspend { totalDurationMsFor("s1") } returns 7_200_000L
                    }
                val result = repo(shelfDao = dao).observeMyShelves("owner1").first()
                result.map { it.id } shouldContainExactly listOf("s1")
                val shelf = result.first()
                shelf.bookCount shouldBe 5
                shelf.ownerId shouldBe "owner1"
                shelf.ownerDisplayName shouldBe "Owner"
                shelf.ownerAvatarColor shouldBe "#112233"
                shelf.coverPaths shouldContainExactly listOf("hash1")
                shelf.totalDurationSeconds shouldBe 7_200L
            }
        }

        test("createShelf dispatches to the service and maps the summary") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { createShelf("New", "", false) } returns
                            WireAppResult.Success(summary("s-new", "New"))
                    }
                val shelf = repo(service = service).createShelf("New", null)
                shelf.id shouldBe "s-new"
                shelf.name shouldBe "New"
            }
        }

        test("createShelf throws when the service returns a failure (no optimistic write)") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { createShelf("Dup", "", false) } returns
                            WireAppResult.Failure(ValidationError(message = "duplicate"))
                    }
                shouldThrow<Exception> { repo(service = service).createShelf("Dup", null) }
            }
        }

        test("deleteShelf dispatches to the service") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { deleteShelf(ShelfId("s1")) } returns WireAppResult.Success(Unit)
                    }
                repo(service = service).deleteShelf("s1")
            }
        }

        test("removeBookFromShelf dispatches to the service") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend {
                            removeBookFromShelf(
                                ShelfId("s1"),
                                com.calypsan.listenup.core
                                    .BookId("b1"),
                            )
                        } returns
                            WireAppResult.Success(Unit)
                    }
                repo(service = service).removeBookFromShelf("s1", "b1")
            }
        }

        test("CancellationException from the service is re-raised, not swallowed") {
            runTest {
                val service =
                    mock<ShelfService> {
                        everySuspend { deleteShelf(ShelfId("s1")) } throws CancellationException("cancelled")
                    }
                shouldThrow<CancellationException> { repo(service = service).deleteShelf("s1") }
            }
        }
    })
