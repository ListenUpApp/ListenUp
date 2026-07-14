package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.CollectionShareDao
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity
import com.calypsan.listenup.client.data.local.db.CollectionWithBookCount
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.CollectionId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [CollectionRepositoryImpl] — Room reads + RPC-dispatched writes.
 *
 * Observation maps Room projections to domain (including JOIN-derived `bookCount`);
 * the still-online `create` dispatches to a faked [CollectionService] and bridges the
 * contract-layer `WireAppResult` to the client [AppResult]. The offline-first surfaces
 * (`rename`, `delete`, `addBook`, `removeBook`) are covered by [CollectionRepositoryOfflineTest].
 */
class CollectionRepositoryImplTest :
    FunSpec({

        fun repo(
            // autofill: create() now reads revisionOf() and calls upsert() for the optimistic mirror.
            collectionDao: CollectionDao = mock(MockMode.autofill),
            bookDao: CollectionBookDao = mock(),
            shareDao: CollectionShareDao = mock(),
            service: CollectionService = mock(),
        ): CollectionRepositoryImpl =
            CollectionRepositoryImpl(collectionDao, bookDao, shareDao, RpcChannel.forTest(service), noopOfflineEditor())

        fun entity(
            id: String,
            name: String,
        ) = CollectionEntity(
            id = id,
            libraryId = "lib1",
            ownerId = "owner1",
            name = name,
            isInbox = false,
            revision = 1L,
            deletedAt = null,
            updatedAt = 100L,
        )

        fun summary(
            id: String,
            name: String,
        ) = CollectionSummary(
            id = CollectionId(id),
            name = name,
            ownerId = UserId("owner1"),
            isInbox = false,
            bookCount = 3L,
            callerPermission = SharePermission.Write,
            isOwner = true,
        )

        test("observeCollections maps Room rows to domain with JOIN-derived bookCount") {
            runTest {
                val dao = mock<CollectionDao>()
                every { dao.observeAllWithBookCount() } returns
                    flowOf(listOf(CollectionWithBookCount(entity("c1", "Alpha"), bookCount = 5)))
                val result = repo(collectionDao = dao).observeCollections().first()
                result.map { it.id } shouldContainExactly listOf("c1")
                result.first().bookCount shouldBe 5
            }
        }

        test("observeCollectionBooks delegates to the junction DAO") {
            runTest {
                val bookDao = mock<CollectionBookDao>()
                every { bookDao.observeBookIds("c1") } returns flowOf(listOf("b1", "b2"))
                repo(bookDao = bookDao).observeCollectionBooks("c1").first() shouldContainExactly listOf("b1", "b2")
            }
        }

        test("observeShares maps share entities to domain") {
            runTest {
                val shareDao = mock<CollectionShareDao>()
                every { shareDao.observeForCollection("c1") } returns
                    flowOf(
                        listOf(
                            CollectionShareEntity(
                                id = "s1",
                                collectionId = "c1",
                                sharedWithUserId = "u1",
                                sharedByUserId = "owner1",
                                permission = "write",
                                revision = 1L,
                                deletedAt = null,
                                updatedAt = 100L,
                            ),
                        ),
                    )
                val result = repo(shareDao = shareDao).observeShares("c1").first()
                result.first().sharedWithUserId shouldBe "u1"
                result.first().permission shouldBe SharePermission.Write
            }
        }

        test("create dispatches to the service and maps the summary") {
            runTest {
                val service = mock<CollectionService>()
                everySuspend { service.createCollection("lib1", "New") } returns
                    WireAppResult.Success(summary("c-new", "New"))
                val result = repo(service = service).create("lib1", "New")
                val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
                (success.data as com.calypsan.listenup.client.domain.model.Collection).id shouldBe "c-new"
            }
        }

        test("create propagates a wire failure as a client failure (no optimistic write)") {
            runTest {
                val service = mock<CollectionService>()
                everySuspend { service.createCollection("lib1", "Dup") } returns
                    WireAppResult.Failure(ValidationError(message = "duplicate"))
                val result = repo(service = service).create("lib1", "Dup")
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.message shouldBe "duplicate"
            }
        }

        // Offline-first visibility: a created collection is written to Room immediately so it shows in
        // the list without waiting for the SSE echo (which may be delayed or offline).
        test("create optimistically mirrors the new collection into Room before the SSE echo") {
            runTest {
                val dao = mock<CollectionDao>(MockMode.autofill)
                everySuspend { dao.revisionOf("c-new") } returns null // not yet echoed
                val service = mock<CollectionService>()
                everySuspend { service.createCollection("lib1", "New") } returns
                    WireAppResult.Success(summary("c-new", "New"))

                repo(collectionDao = dao, service = service).create("lib1", "New")

                verifySuspend { dao.upsert(any()) }
            }
        }

        // Idempotency: the optimistic write is insert-if-absent — it must never clobber a row the
        // authoritative SSE echo already applied (which carries the real, higher revision).
        test("create does not overwrite an already-echoed collection row") {
            runTest {
                val dao = mock<CollectionDao>(MockMode.autofill)
                everySuspend { dao.revisionOf("c-new") } returns 9L // echo already applied
                val service = mock<CollectionService>()
                everySuspend { service.createCollection("lib1", "New") } returns
                    WireAppResult.Success(summary("c-new", "New"))

                repo(collectionDao = dao, service = service).create("lib1", "New")

                verifySuspend(mode = VerifyMode.not) { dao.upsert(any()) }
            }
        }
    })
