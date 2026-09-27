package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.testing.registerTestSyncDomains
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest

/**
 * Reconcile-on-drain and the DRIFT-1 heal re-read the entity behind an outbox op. The op's key is
 * what the repository chose — a book for a rating, a `"parent:child"` pair for a junction — so it is
 * translated into a fetch the domain can serve, never sent as though it were a wire id.
 */
class DrainReconcilerOutboxKeyTest :
    FunSpec({

        test("reconcile-on-drain fetches each domain by what its op keys name, one fetch per domain") {
            withReconciler { reconciler, fetches ->
                reconciler.reconcileSentEntities(
                    listOf(
                        SentEntityRef("book_ratings", "b1"),
                        SentEntityRef("book_ratings", "b2"),
                        SentEntityRef("book_moods", "b1:m1"),
                        SentEntityRef("book_moods", "b1:m2"),
                        SentEntityRef("book_tags", "b3:t1"),
                        SentEntityRef("collection_books", "c1:b1"),
                        SentEntityRef("books", "bk1"),
                    ),
                )

                fetches shouldContainExactlyInAnyOrder
                    listOf(
                        "book_ratings" to TargetedFetch.ByBookIds(listOf("b1", "b2")),
                        "book_moods" to TargetedFetch.ByBookIds(listOf("b1")),
                        "book_tags" to TargetedFetch.ByBookIds(listOf("b3")),
                        "collection_books" to TargetedFetch.ByCollectionIds(listOf("c1")),
                        "books" to TargetedFetch.ByIds(listOf("bk1")),
                    )
            }
        }

        test("heal re-reads a rating by its book") {
            withReconciler { reconciler, fetches ->
                reconciler.healEntity(SentEntityRef("book_ratings", "b1"))
                fetches shouldContainExactly listOf("book_ratings" to TargetedFetch.ByBookIds(listOf("b1")))
            }
        }

        test("heal re-reads a junction by its parent") {
            withReconciler { reconciler, fetches ->
                reconciler.healEntity(SentEntityRef("book_tags", "b1:t1"))
                fetches shouldContainExactly listOf("book_tags" to TargetedFetch.ByBookIds(listOf("b1")))
            }
        }

        test("heal still re-reads a wire-keyed domain by id") {
            withReconciler { reconciler, fetches ->
                reconciler.healEntity(SentEntityRef("tags", "t1"))
                fetches shouldContainExactly listOf("tags" to TargetedFetch.ByIds(listOf("t1")))
            }
        }

        test("heal skips shelf_books, which no targeted fetch can serve") {
            withReconciler { reconciler, fetches ->
                reconciler.healEntity(SentEntityRef("shelf_books", "s1:b1"))
                fetches.shouldBeEmpty()
            }
        }
    })

private fun withReconciler(block: suspend (DrainReconciler, List<Pair<String, TargetedFetch>>) -> Unit) =
    runTest {
        val db: ListenUpDatabase = createInMemoryTestDatabase()
        try {
            val registry = ClientSyncDomainRegistry()
            registerTestSyncDomains(db = db, registry = registry)
            val catchUp = RecordingTargetedCatchUp()
            val queue =
                PendingOperationQueue(
                    dao = db.pendingOperationV2Dao(),
                    sender = PendingOperationSender { AppResult.Success(Unit) },
                )
            block(DrainReconciler(queue, registry, catchUp, Mutex()), catchUp.fetches)
        } finally {
            db.close()
        }
    }

/** Records every targeted fetch as `(domain, fetch)`; every other pull is a no-op. */
private class RecordingTargetedCatchUp : CatchUp {
    val fetches = mutableListOf<Pair<String, TargetedFetch>>()

    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> {
        fetches += handler.domainName to fetch
        return AppResult.Success(emptySet())
    }

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}
