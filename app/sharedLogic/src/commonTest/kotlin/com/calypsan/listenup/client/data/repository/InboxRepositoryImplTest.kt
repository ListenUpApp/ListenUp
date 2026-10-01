package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.LibraryId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import dev.mokkery.MockMode
import dev.mokkery.every
import dev.mokkery.verify.VerifyMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * Tests for [InboxRepositoryImpl] over the `CollectionService.listInbox` /
 * `CollectionService.releaseBooks` RPC surface.
 *
 * The repository is a thin pass-through to [CollectionService] via [RpcChannel]; these
 * tests pin the delegation and the domain (`String`) ↔ contract (typed id) mapping at
 * the boundary, including the per-book assignment map.
 */
class InboxRepositoryImplTest :
    FunSpec({

        fun buildRepo(
            service: CollectionService,
            collectionBookDao: CollectionBookDao = mock(MockMode.autoUnit),
        ): InboxRepositoryImpl =
            InboxRepositoryImpl(
                channel = RpcChannel.forTest(service),
                scannerChannel = RpcChannel.forTest(mock<ScannerService>()),
                collectionBookDao = collectionBookDao,
            )

        test("listInbox forwards to the service and returns the mapped book ids") {
            runTest {
                val service = mock<CollectionService>()
                everySuspend { service.listInbox(LibraryId("lib1")) } returns
                    AppResult.Success(listOf(BookId("b1"), BookId("b2")))

                val result = buildRepo(service).listInbox("lib1")

                val success = result.shouldBeInstanceOf<AppResult.Success<List<String>>>()
                success.data shouldBe listOf("b1", "b2")
            }
        }

        test("releaseBooks forwards the per-book assignment map verbatim") {
            runTest {
                val service = mock<CollectionService>()
                val typedAssignments = mapOf(BookId("b1") to listOf(CollectionId("col1")), BookId("b2") to emptyList())
                everySuspend { service.releaseBooks(LibraryId("lib1"), typedAssignments) } returns AppResult.Success(Unit)

                val assignments = mapOf("b1" to listOf("col1"), "b2" to emptyList())
                buildRepo(service).releaseBooks("lib1", assignments).shouldBeInstanceOf<AppResult.Success<Unit>>()

                verifySuspend { service.releaseBooks(LibraryId("lib1"), typedAssignments) }
            }
        }

        test("listInbox propagates a failure") {
            runTest {
                val service = mock<CollectionService>()
                everySuspend { service.listInbox(any()) } returns AppResult.Failure(ValidationError(message = "forbidden"))

                buildRepo(service).listInbox("lib1").shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("observeHeldBookIds types the Room ids and keeps the oldest-first order") {
            runTest {
                val dao = mock<CollectionBookDao> { every { observeHeldBookIds() } returns flowOf(listOf("b2", "b1")) }

                val held = buildRepo(mock(), dao).observeHeldBookIds().first()

                held.toList() shouldBe listOf(BookId("b2"), BookId("b1"))
            }
        }

        test("a committed release takes the books out of the local inbox at once") {
            runTest {
                val service = mock<CollectionService>()
                everySuspend { service.releaseBooks(any(), any()) } returns AppResult.Success(Unit)
                val dao = mock<CollectionBookDao>(MockMode.autoUnit)

                buildRepo(service, dao)
                    .releaseBooks("lib1", mapOf("b1" to emptyList(), "b2" to emptyList()))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                verifySuspend { dao.tombstoneHeldRows(listOf("b1", "b2"), any()) }
            }
        }

        test("a refused release leaves the local inbox untouched") {
            runTest {
                val service = mock<CollectionService>()
                everySuspend { service.releaseBooks(any(), any()) } returns
                    AppResult.Failure(ValidationError(message = "forbidden"))
                val dao = mock<CollectionBookDao>(MockMode.autoUnit)

                buildRepo(service, dao)
                    .releaseBooks("lib1", mapOf("b1" to emptyList()))
                    .shouldBeInstanceOf<AppResult.Failure>()

                verifySuspend(VerifyMode.not) { dao.tombstoneHeldRows(any(), any()) }
            }
        }
    })
