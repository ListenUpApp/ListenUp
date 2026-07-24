package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.collectionsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

/**
 * Pins the scoped prune primitive [AccessFilteredSyncHandler.pruneWithin] — the removal half of the
 * scoped `AccessChanged` delta. The load-bearing property is the **substrate protection**: a live
 * row outside the scope is never a candidate, so a targeted delta can never tombstone it, no matter
 * what the accessible set says. Without that, a scoped revoke would nuke the public library the
 * client mirrors but never enumerates.
 */
class PruneWithinTest :
    FunSpec({

        fun handler(db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase) =
            collectionsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                as AccessFilteredSyncHandler

        test("prunes a candidate that is no longer accessible") {
            runBlocking {
                val db = createInMemoryTestDatabase()
                try {
                    val h = handler(db)
                    (h as SyncDomainHandler<CollectionSyncPayload>).onCatchUpItem(collectionPayload("c1"), false)
                    h.onCatchUpItem(collectionPayload("c2"), false)

                    // c2 is a candidate that did NOT come back accessible → doomed.
                    (h as AccessFilteredSyncHandler)
                        .pruneWithin(candidateIds = setOf("c1", "c2"), accessibleIds = setOf("c1"), now = 1L)

                    db.collectionDao().getById("c1").shouldNotBeNull()
                    db.collectionDao().getById("c2") shouldBe null
                } finally {
                    db.close()
                }
            }
        }

        test("a live row OUTSIDE the scope is never touched, even when it is not in the accessible set") {
            runBlocking {
                val db = createInMemoryTestDatabase()
                try {
                    val h = handler(db)
                    val typed = h as SyncDomainHandler<CollectionSyncPayload>
                    typed.onCatchUpItem(collectionPayload("in-scope"), false)
                    // 'substrate' is a live row the client mirrors but the delta never names.
                    typed.onCatchUpItem(collectionPayload("substrate"), false)

                    // Scope names only 'in-scope'; accessibleIds is empty (in-scope was revoked).
                    // 'substrate' is NOT a candidate, so it must survive despite being absent from
                    // accessibleIds — this is the assertion the whole design turns on.
                    (h as AccessFilteredSyncHandler)
                        .pruneWithin(candidateIds = setOf("in-scope"), accessibleIds = emptySet(), now = 1L)

                    db.collectionDao().getById("in-scope") shouldBe null
                    db.collectionDao().getById("substrate").shouldNotBeNull()
                } finally {
                    db.close()
                }
            }
        }

        test("an empty candidate set is a no-op") {
            runBlocking {
                val db = createInMemoryTestDatabase()
                try {
                    val h = handler(db)
                    (h as SyncDomainHandler<CollectionSyncPayload>).onCatchUpItem(collectionPayload("c1"), false)

                    (h as AccessFilteredSyncHandler)
                        .pruneWithin(candidateIds = emptySet(), accessibleIds = emptySet(), now = 1L)

                    db.collectionDao().getById("c1").shouldNotBeNull()
                } finally {
                    db.close()
                }
            }
        }
    })

private fun collectionPayload(id: String): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "lib1",
        ownerId = "u1",
        name = "Collection $id",
        isInbox = false,
        revision = 1L,
        updatedAt = 100L,
        deletedAt = null,
    )
