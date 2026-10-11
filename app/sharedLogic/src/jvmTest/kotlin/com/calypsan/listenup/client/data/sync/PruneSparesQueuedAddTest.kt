package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.bookTagsDomain
import com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest

private const val NOW = 9_000L

/**
 * An access prune tombstones every live row the server did not return. An optimistic add sits at
 * `revision = 0` under a client-minted id until its echo arrives, so a prune that runs while the add
 * is still queued would make the row vanish and then reappear. The prune therefore spares
 * unacknowledged rows while the domain has a dispatchable op queued, and cleans them up once the
 * queue is empty (the add drained or dead-lettered).
 */
class PruneSparesQueuedAddTest :
    FunSpec({

        test("pruneTo spares a revision-0 book tag while the domain has a queued op") {
            withBookTags(queued = true) { handler, db ->
                handler.pruneTo(setOf("wire-acked"), NOW)
                db.bookTagDao().liveIds() shouldContainExactlyInAnyOrder listOf("wire-acked", "minted")
            }
        }

        test("pruneTo tombstones a revision-0 book tag once no op is queued") {
            withBookTags(queued = false) { handler, db ->
                handler.pruneTo(setOf("wire-acked"), NOW)
                db.bookTagDao().liveIds() shouldContainExactlyInAnyOrder listOf("wire-acked")
            }
        }

        test("pruneTo still tombstones an acknowledged book tag the server dropped, even with an op queued") {
            withBookTags(queued = true) { handler, db ->
                handler.pruneTo(setOf("minted"), NOW)
                db.bookTagDao().liveIds() shouldContainExactlyInAnyOrder listOf("minted")
            }
        }

        test("pruneWithin spares a revision-0 book tag while the domain has a queued op") {
            withBookTags(queued = true) { handler, db ->
                handler.pruneWithin(setOf("wire-acked", "minted"), setOf("wire-acked"), NOW)
                db.bookTagDao().liveIds() shouldContainExactlyInAnyOrder listOf("wire-acked", "minted")
            }
        }

        test("pruneTo spares a revision-0 collection membership while the domain has a queued op") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.collectionBookDao().upsert(CollectionBookEntity("c1", "b1", "wire-acked", 1L, revision = 5))
                    db.collectionBookDao().upsert(CollectionBookEntity("c1", "b2", "minted", 2L, revision = 0))
                    val handler =
                        collectionBooksDomain(db)
                            .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry(), hasQueuedOpIn = { true })
                            as AccessFilteredSyncHandler
                    handler.pruneTo(setOf("wire-acked"), NOW)
                    db.collectionBookDao().liveSyncIds() shouldContainExactlyInAnyOrder listOf("wire-acked", "minted")
                } finally {
                    db.close()
                }
            }
        }
    })

private fun withBookTags(
    queued: Boolean,
    block: suspend (AccessFilteredSyncHandler, ListenUpDatabase) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        db.bookTagDao().upsert(BookTagEntity("b1", "t1", syncId = "wire-acked", createdAt = 1L, revision = 5))
        db.bookTagDao().upsert(BookTagEntity("b2", "t2", syncId = "minted", createdAt = 2L, revision = 0))
        val handler =
            bookTagsDomain(db)
                .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry(), hasQueuedOpIn = { queued })
                as AccessFilteredSyncHandler
        block(handler, db)
    } finally {
        db.close()
    }
}
