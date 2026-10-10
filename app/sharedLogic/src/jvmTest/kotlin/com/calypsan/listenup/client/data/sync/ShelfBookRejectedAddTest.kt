package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.ShelfRepositoryImpl
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.testing.registerTestSyncDomains
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * An optimistic "add book to shelf" is written at `revision = 0` under a client-minted id. When the
 * server refuses the op, nothing can re-read the entity (the domain has no targeted fetch), so the
 * heal must undo the optimistic row itself — and a row the server never had must not make the
 * domain's local digest differ from the server's.
 */
class ShelfBookRejectedAddTest :
    FunSpec({

        fun acknowledged(
            shelfId: String,
            bookId: String,
            revision: Long,
        ) = ShelfBookEntity(
            id = "wire-$shelfId-$bookId",
            shelfId = shelfId,
            bookId = bookId,
            sortOrder = 0,
            revision = revision,
            deletedAt = null,
            updatedAt = 100L,
            createdAt = 50L,
        )

        suspend fun withHarness(block: suspend (Harness) -> Unit) {
            val db = createInMemoryTestDatabase()
            try {
                block(Harness(db))
            } finally {
                db.close()
            }
        }

        test("a shelf add the server refuses is removed from the shelf") {
            runTest {
                withHarness { h ->
                    h.repo.addBooksToShelf(ShelfId("s1"), listOf(BookId("b1")))
                    h.db.shelfBookDao().findByShelfAndBook("s1", "b1").shouldNotBeNull()

                    h.drainAndHeal()

                    h.db
                        .shelfBookDao()
                        .findByShelfAndBook("s1", "b1")
                        .shouldBeNull()
                }
            }
        }

        test("a queued shelf add does not make the local digest differ from the server's") {
            runTest {
                withHarness { h ->
                    h.repo.addBooksToShelf(ShelfId("s1"), listOf(BookId("b1")))
                    val optimisticId =
                        h.db
                            .shelfBookDao()
                            .findByShelfAndBook("s1", "b1")
                            .shouldNotBeNull()
                            .id

                    h.db
                        .shelfBookDao()
                        .digestRows(Long.MAX_VALUE)
                        .map { it.id } shouldNotContain optimisticId
                }
            }
        }

        test("a shelf membership the server has acknowledged is still in the digest") {
            runTest {
                withHarness { h ->
                    h.db.shelfBookDao().upsert(acknowledged("s1", "b1", revision = 5))

                    h.db
                        .shelfBookDao()
                        .digestRows(Long.MAX_VALUE)
                        .map { it.id } shouldContain "wire-s1-b1"
                }
            }
        }

        test("refusing an add does not remove an acknowledged membership of the same pair") {
            runTest {
                withHarness { h ->
                    h.db.shelfBookDao().upsert(acknowledged("s1", "b1", revision = 5))
                    h.queue.enqueue(OutboxChannels.ShelfBooks, "s1:b1", OpKind.Create, "{}", "u1")

                    h.drainAndHeal()

                    h.db
                        .shelfBookDao()
                        .findByShelfAndBook("s1", "b1")
                        .shouldNotBeNull()
                        .revision shouldBe 5L
                }
            }
        }
    })

/** Real Room, real repository, real queue and reconciler; the sender refuses everything. */
private class Harness(
    val db: ListenUpDatabase,
) {
    val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender = PendingOperationSender { AppResult.Failure(AuthError.PermissionDenied()) },
            nowMillis = { 1_000L },
        )
    private val registry = ClientSyncDomainRegistry().also { registerTestSyncDomains(db = db, registry = it) }
    private val reconciler = DrainReconciler(queue, registry, NoPullCatchUp, Mutex())

    val repo =
        ShelfRepositoryImpl(
            dao = db.shelfDao(),
            shelfBookDao = db.shelfBookDao(),
            userDao = mock<UserDao> { everySuspend { getCurrentUser() } returns null },
            channel = RpcChannel.forTest(mock<com.calypsan.listenup.api.ShelfService>()),
            offlineEditor =
                OfflineEditor(
                    pendingQueue = queue,
                    transactionRunner =
                        object : TransactionRunner {
                            override suspend fun <R> atomically(block: suspend () -> R): R = block()
                        },
                    authSession = FakeAuthSession(userId = "u1"),
                ),
            collectionBookDao = db.collectionBookDao(),
        )

    /** Drain the refused op, then heal exactly as the engine's heal-drain collector does. */
    suspend fun drainAndHeal() {
        queue.drain()
        val ref = withTimeout(5.seconds) { queue.observeHealRequests().first() }
        reconciler.healEntity(ref)
    }
}

/** Nothing to pull: the heal under test must not depend on a server round-trip. */
private object NoPullCatchUp : CatchUp {
    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}
