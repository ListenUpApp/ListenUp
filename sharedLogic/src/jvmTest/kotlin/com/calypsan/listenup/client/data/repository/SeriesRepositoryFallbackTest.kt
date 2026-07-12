package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.result.AppResult as WireResult
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.domains.seriesDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
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
 * Verifies the "Never Stranded" RPC-fallback in [SeriesRepositoryImpl.observeById]:
 * Room-hit skips RPC, cache miss fires RPC + write-through, offline skips RPC,
 * and a server 404 (null payload) leaves Room empty without crashing.
 *
 * Mirrors [BookRepositoryFallbackTest]: real in-memory Room database, Mokkery mocks
 * for the RPC factory and network monitor, Turbine for Flow assertions.
 */
class SeriesRepositoryFallbackTest :
    FunSpec({

        test("observe a series present in Room emits it and never calls getSeries") {
            withTestRepo(online = true) { repo, db, service ->
                seedRoom(db, id = "s1", name = "The Stormlight Archive")

                repo.observeById("s1").test {
                    awaitItem()?.name shouldBe "The Stormlight Archive"
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(0)) { service.getSeries(any()) }
            }
        }

        test("observe a cache-missing series while online fetches via getSeries and Room re-emits") {
            withTestRepo(online = true) { repo, db, service ->
                everySuspend { service.getSeries(SeriesId("s2")) } returns
                    WireResult.Success(payload(id = "s2", name = "Mistborn"))

                repo.observeById("s2").test {
                    // First emission: cache miss → null.
                    awaitItem() shouldBe null
                    // Write-through populates Room → the Room Flow re-emits.
                    awaitItem()?.name shouldBe "Mistborn"
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(1)) { service.getSeries(SeriesId("s2")) }
            }
        }

        test("observe a cache-missing series while offline emits null and never calls getSeries") {
            withTestRepo(online = false) { repo, db, service ->
                repo.observeById("s3").test {
                    awaitItem() shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }

                verifySuspend(exactly(0)) { service.getSeries(any()) }
            }
        }

        test("when getSeries returns null the flow emits null and does not write to Room") {
            withTestRepo(online = true) { repo, db, service ->
                everySuspend { service.getSeries(SeriesId("s4")) } returns
                    WireResult.Success(null)

                repo.observeById("s4").test {
                    awaitItem() shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }

                db.seriesDao().getById("s4") shouldBe null
            }
        }

        test("when getSeries fails the flow emits null without retrying") {
            withTestRepo(online = true) { repo, db, service ->
                everySuspend { service.getSeries(SeriesId("s5")) } returns
                    WireResult.Failure(
                        com.calypsan.listenup.api.error
                            .InternalError(),
                    )

                repo.observeById("s5").test {
                    awaitItem() shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }

                // Only called once — the attemptedFetch guard prevents retries.
                verifySuspend(exactly(1)) { service.getSeries(SeriesId("s5")) }
                db.seriesDao().getById("s5") shouldBe null
            }
        }
    })

/**
 * Builds an in-memory database, a real `series` composed handler for
 * write-through, a [SeriesRepositoryImpl] wired with mocked RPC + network,
 * runs [block], and closes the database afterwards.
 */
private fun withTestRepo(
    online: Boolean = true,
    block: suspend (SeriesRepositoryImpl, ListenUpDatabase, SeriesService) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        val service: SeriesService = mock()

        val networkMonitor: NetworkMonitor = mock()
        every { networkMonitor.isOnline() } returns online

        val imageStorage: ImageStorage = mock()
        every { imageStorage.exists(any()) } returns false

        val transactionRunner = RoomTransactionRunner(db)
        val syncHandler = seriesDomain(db).toHandler(transactionRunner, ClientSyncDomainRegistry())

        val repo =
            SeriesRepositoryImpl(
                seriesDao = db.seriesDao(),
                bookDao = db.bookDao(),
                searchDao = db.searchDao(),
                networkMonitor = networkMonitor,
                imageStorage = imageStorage,
                channel = RpcChannel.forTest(service),
                searchChannel = RpcChannel.forTest(mock<SearchService>()),
                seriesSyncHandler = syncHandler,
            )

        block(repo, db, service)
    } finally {
        db.close()
    }
}

/** Seeds a minimal series into Room via the canonical sync write path. */
private suspend fun seedRoom(
    db: ListenUpDatabase,
    id: String,
    name: String,
) {
    val handler = seriesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
    handler.onCatchUpItem(payload(id = id, name = name), isTombstone = false)
}

private fun payload(
    id: String,
    name: String,
): SeriesSyncPayload =
    SeriesSyncPayload(
        id = id,
        name = name,
        sortName = name,
        revision = 1L,
        updatedAt = 100L,
        createdAt = 1L,
        deletedAt = null,
    )
