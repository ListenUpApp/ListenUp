package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.result.AppResult as WireResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.contributorsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Verifies the "Never Stranded" RPC-fallback in [ContributorRepositoryImpl.observeById]:
 * Room-hit skips RPC, cache miss fires RPC + write-through, offline skips RPC,
 * and a server 404 (null payload) leaves Room empty without crashing.
 *
 * Mirrors [BookRepositoryFallbackTest]: real in-memory Room database, Mokkery mocks
 * for the RPC factory and network monitor, Turbine for Flow assertions.
 */
class ContributorRepositoryFallbackTest :
    FunSpec({

        test("observe a contributor present in Room emits it and never calls getContributor") {
            withTestRepo(online = true) { repo, db, service ->
                seedRoom(db, id = "c1", name = "Brandon Sanderson")

                repo.observeById("c1").test {
                    awaitItem()?.name shouldBe "Brandon Sanderson"
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(0)) { service.getContributor(any()) }
            }
        }

        test("observe a cache-missing contributor while online fetches via getContributor and Room re-emits") {
            withTestRepo(online = true) { repo, db, service ->
                everySuspend { service.getContributor(ContributorId("c2")) } returns
                    WireResult.Success(payload(id = "c2", name = "Robin Hobb"))

                repo.observeById("c2").test {
                    // First emission: cache miss → null.
                    awaitItem() shouldBe null
                    // Write-through populates Room → the Room Flow re-emits.
                    awaitItem()?.name shouldBe "Robin Hobb"
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(1)) { service.getContributor(ContributorId("c2")) }
            }
        }

        test("observe a cache-missing contributor while offline emits null and never calls getContributor") {
            withTestRepo(online = false) { repo, db, service ->
                repo.observeById("c3").test {
                    awaitItem() shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(0)) { service.getContributor(any()) }
            }
        }

        test("when getContributor returns null the flow emits null and does not write to Room") {
            withTestRepo(online = true) { repo, db, service ->
                everySuspend { service.getContributor(ContributorId("c4")) } returns
                    WireResult.Success(null)

                repo.observeById("c4").test {
                    awaitItem() shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }

                db.contributorDao().getById("c4") shouldBe null
            }
        }

        test("when getContributor fails the flow emits null without retrying") {
            withTestRepo(online = true) { repo, db, service ->
                everySuspend { service.getContributor(ContributorId("c5")) } returns
                    WireResult.Failure(
                        com.calypsan.listenup.api.error
                            .InternalError(),
                    )

                repo.observeById("c5").test {
                    awaitItem() shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }

                // Only called once — the attemptedFetch guard prevents retries.
                verifySuspend(exactly(1)) { service.getContributor(ContributorId("c5")) }
                db.contributorDao().getById("c5") shouldBe null
            }
        }
    })

/**
 * Builds an in-memory database, a real [contributorsDomain] handler for
 * write-through, a [ContributorRepositoryImpl] wired with mocked RPC + network,
 * runs [block], and closes the database afterwards.
 */
private fun withTestRepo(
    online: Boolean = true,
    block: suspend (ContributorRepositoryImpl, ListenUpDatabase, ContributorService) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        val service: ContributorService = mock()

        val networkMonitor: NetworkMonitor = mock()
        every { networkMonitor.isOnline() } returns online

        val imageStorage: ImageStorage = mock()
        every { imageStorage.exists(any()) } returns false

        val transactionRunner = RoomTransactionRunner(db)
        val syncHandler =
            contributorsDomain(db, stubImageStorage()).toHandler(transactionRunner, ClientSyncDomainRegistry())

        val repo =
            ContributorRepositoryImpl(
                contributorDao = db.contributorDao(),
                bookDao = db.bookDao(),
                searchDao = db.searchDao(),
                networkMonitor = networkMonitor,
                imageStorage = imageStorage,
                channel = RpcChannel.forTest(service),
                contributorSyncHandler = syncHandler,
            )

        block(repo, db, service)
    } finally {
        db.close()
    }
}

/** Seeds a minimal contributor into Room via the canonical sync write path. */
private suspend fun seedRoom(
    db: ListenUpDatabase,
    id: String,
    name: String,
) {
    val handler =
        contributorsDomain(db, stubImageStorage()).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
    handler.onCatchUpItem(payload(id = id, name = name), isTombstone = false)
}

private fun payload(
    id: String,
    name: String,
): ContributorSyncPayload =
    ContributorSyncPayload(
        id = id,
        name = name,
        sortName = name,
        revision = 1L,
        updatedAt = 100L,
        createdAt = 1L,
        deletedAt = null,
    )
