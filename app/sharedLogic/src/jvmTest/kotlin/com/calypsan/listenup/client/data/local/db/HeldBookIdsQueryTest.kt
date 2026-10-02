package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Pins the one definition of "held" ([HELD_BOOK_IDS_SQL]) through the [CollectionBookDao] queries
 * every held surface reads: a live membership in a live INBOX collection — nothing else.
 */
class HeldBookIdsQueryTest :
    FunSpec({
        test("held ids are exactly the live INBOX memberships") {
            withHeldBookDb { db ->
                db.collectionDao().upsert(
                    CollectionEntity(
                        id = "col-scifi",
                        libraryId = "lib1",
                        ownerId = "root",
                        name = "Sci-fi",
                        isInbox = false,
                        revision = 1L,
                        updatedAt = 100L,
                    ),
                )
                HeldBookFixture.hold(db, "held-1")
                HeldBookFixture.hold(db, "held-2")
                HeldBookFixture.publish(db, "public-1")
                db.collectionBookDao().upsert(HeldBookFixture.membership("col-scifi", "curated-1"))
                db.collectionBookDao().upsert(
                    HeldBookFixture.membership(HeldBookFixture.INBOX, "released-1", deletedAt = 50L),
                )

                db.collectionBookDao().heldBookIds() shouldContainExactlyInAnyOrder listOf("held-1", "held-2")
            }
        }

        test("a tombstoned INBOX collection holds nothing") {
            withHeldBookDb { db ->
                HeldBookFixture.hold(db, "held-1")
                db.collectionDao().softDelete(HeldBookFixture.INBOX, deletedAt = 50L, revision = 2L)

                db.collectionBookDao().heldBookIds().shouldBeEmpty()
            }
        }

        test("a member's database — INBOX rows never arrive — yields an empty held set") {
            withHeldBookDb(seedSystemCollections = false) { db ->
                db.collectionDao().upsert(
                    CollectionEntity(
                        id = HeldBookFixture.ALL_BOOKS,
                        libraryId = "lib1",
                        ownerId = "root",
                        name = "All Books",
                        isInbox = false,
                        isSystem = true,
                        revision = 1L,
                        updatedAt = 100L,
                    ),
                )
                HeldBookFixture.publish(db, "public-1")

                db.collectionBookDao().observeHeldBookIds().test {
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeHeldBookIds lists the oldest hold first") {
            withHeldBookDb { db ->
                HeldBookFixture.hold(db, "newer", heldAt = 20L)
                HeldBookFixture.hold(db, "older", heldAt = 10L)

                db.collectionBookDao().observeHeldBookIds().test {
                    awaitItem() shouldContainExactly listOf("older", "newer")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeHeldBookIds follows a hold and its release live") {
            withHeldBookDb { db ->
                db.collectionBookDao().observeHeldBookIds().test {
                    awaitItem().shouldBeEmpty()

                    HeldBookFixture.hold(db, "b1")
                    awaitItemMatching { it == listOf("b1") }

                    HeldBookFixture.applyReleaseEcho(db, "b1")
                    awaitItemMatching { it.isEmpty() }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("tombstoneHeldRows ends only the INBOX memberships of the named books, keeping their revision") {
            withHeldBookDb { db ->
                HeldBookFixture.hold(db, "b1")
                HeldBookFixture.hold(db, "b2")
                HeldBookFixture.publish(db, "b3")

                db.collectionBookDao().tombstoneHeldRows(listOf("b1", "b3"), now = 900L)

                db.collectionBookDao().heldBookIds() shouldContainExactly listOf("b2")
                db
                    .collectionBookDao()
                    .findByKey(HeldBookFixture.ALL_BOOKS, "b3")
                    .shouldNotBeNull()
                    .deletedAt shouldBe null
                val ended = db.collectionBookDao().findByKey(HeldBookFixture.INBOX, "b1").shouldNotBeNull()
                ended.deletedAt shouldBe 900L
                // Preserved, so the server's own tombstone echo (a higher revision) still applies.
                ended.revision shouldBe 1L
            }
        }

        test("tombstoneHeldRows leaves an INBOX row the echo already ended untouched") {
            withHeldBookDb { db ->
                HeldBookFixture.hold(db, "b1")
                HeldBookFixture.applyReleaseEcho(db, "b1")

                db.collectionBookDao().tombstoneHeldRows(listOf("b1"), now = 900L)

                val echoed = db.collectionBookDao().findByKey(HeldBookFixture.INBOX, "b1").shouldNotBeNull()
                echoed.deletedAt shouldBe 5_000L
                echoed.revision shouldBe 2L
            }
        }

        test("tombstoneHeldRows leaves a held book's other memberships alone") {
            withHeldBookDb { db ->
                // Mid-release race: the curated row has landed but the INBOX row has not ended yet.
                db.collectionDao().upsert(
                    CollectionEntity(
                        id = "col-scifi",
                        libraryId = "lib1",
                        ownerId = "root",
                        name = "Sci-fi",
                        isInbox = false,
                        revision = 1L,
                        updatedAt = 100L,
                    ),
                )
                HeldBookFixture.hold(db, "b1")
                db.collectionBookDao().upsert(HeldBookFixture.membership("col-scifi", "b1"))

                db.collectionBookDao().tombstoneHeldRows(listOf("b1"), now = 900L)

                db.collectionBookDao().heldBookIds().shouldBeEmpty()
                db
                    .collectionBookDao()
                    .findByKey("col-scifi", "b1")
                    .shouldNotBeNull()
                    .deletedAt shouldBe null
            }
        }
    })
