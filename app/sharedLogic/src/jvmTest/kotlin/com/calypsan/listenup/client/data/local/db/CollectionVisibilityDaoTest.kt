package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.ALL_BOOKS
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.hold
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.membership
import com.calypsan.listenup.client.test.collectionShare
import com.calypsan.listenup.client.test.normalCollection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first

/**
 * The Room inputs to collection visibility: which books are restricted (the lock on every book
 * card), and, per book, whether it is held, the live collections it is a live member of, and those
 * collections' live shares. Every query must ignore a tombstoned membership, collection or share,
 * and "held" must be the inbox's own fragment.
 */
class CollectionVisibilityDaoTest :
    FunSpec({
        suspend fun seedCollections(db: ListenUpDatabase) {
            db.collectionDao().upsert(normalCollection("c1", "Kids"))
            db.collectionDao().upsert(normalCollection("dead", "Old", deletedAt = 9L))
        }

        test("restricted ids are books live in a live normal collection, and never held ones") {
            withHeldBookDb { db ->
                seedCollections(db)
                val members = db.collectionBookDao()
                members.upsert(membership("c1", "b-restricted"))
                members.upsert(membership(ALL_BOOKS, "b-public"))
                hold(db, "b-held")
                hold(db, "b-held-curated")
                members.upsert(membership("c1", "b-held-curated"))
                members.upsert(membership("c1", "b-left", deletedAt = 5L))
                members.upsert(membership("dead", "b-dead"))

                members.observeRestrictedBookIds().first() shouldContainExactlyInAnyOrder listOf("b-restricted")
            }
        }

        test("restricted ids re-emit when a book joins a normal collection, and when it is held") {
            withHeldBookDb { db ->
                seedCollections(db)
                val members = db.collectionBookDao()
                members.observeRestrictedBookIds().test {
                    awaitItem().shouldBeEmpty()
                    members.upsert(membership("c1", "b1"))
                    awaitItem() shouldContainExactly listOf("b1")
                    hold(db, "b1")
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a book is held exactly when it is in the inbox's held set") {
            withHeldBookDb { db ->
                seedCollections(db)
                val members = db.collectionBookDao()
                hold(db, "b-held")
                members.upsert(membership(ALL_BOOKS, "b-public"))

                for (id in listOf("b-held", "b-public", "b-never")) {
                    members.observeIsHeld(id).first() shouldBe (id in members.heldBookIds())
                }
                members.observeIsHeld("b-held").first() shouldBe true
            }
        }

        test("collections for a book are the live collections behind its live memberships") {
            withHeldBookDb { db ->
                seedCollections(db)
                db.collectionDao().upsert(normalCollection("c2", "Gone"))
                val members = db.collectionBookDao()
                members.upsert(membership(ALL_BOOKS, "b1"))
                members.upsert(membership("c1", "b1"))
                members.upsert(membership("dead", "b1"))
                members.upsert(membership("c2", "b1", deletedAt = 5L))

                db
                    .collectionDao()
                    .observeCollectionsForBook("b1")
                    .first()
                    .map { it.id } shouldContainExactlyInAnyOrder
                    listOf(ALL_BOOKS, "c1")
            }
        }

        test("shares for a book are the live shares of collections it is live in") {
            withHeldBookDb { db ->
                seedCollections(db)
                db.collectionDao().upsert(normalCollection("c2", "Other"))
                db.collectionBookDao().upsert(membership("c1", "b1"))
                val shares = db.collectionShareDao()
                shares.upsert(collectionShare("c1", "alice"))
                shares.upsert(collectionShare("c1", "bob", deletedAt = 5L))
                shares.upsert(collectionShare("c2", "carol"))

                shares.observeSharesForBook("b1").first().map { it.sharedWithUserId } shouldContainExactly
                    listOf("alice")
            }
        }
    })
