package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Covers the sync-substrate queries on the three collection DAOs (Room v24):
 * [CollectionDao], [CollectionBookDao], [CollectionShareDao].
 *
 * All tests use an isolated in-memory database and clean up via `deleteAll`
 * in [beforeEach] so each test starts from a blank slate.
 */
class CollectionDaoTest :
    FunSpec({
        val db = createInMemoryTestDatabase()
        val collectionDao: CollectionDao = db.collectionDao()
        val bookDao: CollectionBookDao = db.collectionBookDao()
        val shareDao: CollectionShareDao = db.collectionShareDao()

        beforeEach {
            collectionDao.deleteAll()
            bookDao.deleteAll()
            shareDao.deleteAll()
        }
        afterSpec { db.close() }

        // ── CollectionDao: upsert / getById / observe ─────────────────────────

        test("upsert then getById returns the live collection") {
            runTest {
                collectionDao.upsert(collection("c1", "Favorites"))
                val result = collectionDao.getById("c1")
                result shouldNotBe null
                result!!.name shouldBe "Favorites"
            }
        }

        test("observeAllWithBookCount excludes tombstoned collections") {
            runTest {
                collectionDao.upsert(collection("c1", "Alpha"))
                collectionDao.upsert(collection("c2", "Beta", deletedAt = 999L))
                collectionDao.observeAllWithBookCount().test {
                    awaitItem().map { it.collection.id } shouldContainExactly listOf("c1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("softDelete hides a collection from observeAllWithBookCount") {
            runTest {
                collectionDao.upsert(collection("c1", "Alpha"))
                collectionDao.observeAllWithBookCount().test {
                    awaitItem().map { it.collection.id } shouldContainExactly listOf("c1")
                    collectionDao.softDelete("c1", deletedAt = 500L, revision = 2L)
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeAllWithBookCount returns the live membership count via JOIN") {
            runTest {
                collectionDao.upsert(collection("c1", "Alpha"))
                bookDao.upsert(member("c1", "b1"))
                bookDao.upsert(member("c1", "b2"))
                bookDao.upsert(member("c1", "b3", deletedAt = 10L)) // tombstoned, not counted
                collectionDao.observeAllWithBookCount().test {
                    val row = awaitItem().single()
                    row.collection.id shouldBe "c1"
                    row.bookCount shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("liveIds returns only non-tombstoned collection ids") {
            runTest {
                collectionDao.upsert(collection("c1", "Alpha"))
                collectionDao.upsert(collection("c2", "Beta", deletedAt = 1L))
                collectionDao.liveIds().toSet() shouldBe setOf("c1")
            }
        }

        // ── Total-revocation guard: tombstoneNotIn(emptySet()) prunes EVERY live row ──
        //
        // SECURITY-CRITICAL: when the caller loses ALL access to this domain, the reconcile
        // calls tombstoneNotIn(emptySet(), now). SQLite evaluates `id NOT IN ()` as TRUE for
        // every row, so all live rows are tombstoned — the correct total-revocation behaviour.
        // This test pins it: it FAILS if anyone adds an `if (accessibleIds.isEmpty()) return`
        // early-return to the DAO query, which would silently leave revoked content readable.

        test("tombstoneNotIn(emptySet) tombstones EVERY live collection (total revocation)") {
            runTest {
                collectionDao.upsert(collection("c1", "Alpha"))
                collectionDao.upsert(collection("c2", "Beta"))
                collectionDao.liveIds().toSet() shouldBe setOf("c1", "c2")

                collectionDao.tombstoneNotIn(emptySet(), now = 777L)

                collectionDao.liveIds().shouldBeEmpty()
                collectionDao.getById("c1") shouldBe null
                collectionDao.getById("c2") shouldBe null
            }
        }

        test("tombstoneNotIn(emptySet) tombstones EVERY live junction row (total revocation)") {
            runTest {
                bookDao.upsert(member("c1", "b1"))
                bookDao.upsert(member("c1", "b2"))
                bookDao.liveSyntheticIds().toSet() shouldBe setOf("c1:b1", "c1:b2")

                bookDao.tombstoneNotIn(emptySet(), now = 777L)

                bookDao.liveSyntheticIds().shouldBeEmpty()
                bookDao.findByKey("c1", "b1")!!.deletedAt shouldNotBe null
                bookDao.findByKey("c1", "b2")!!.deletedAt shouldNotBe null
            }
        }

        test("tombstoneNotIn(emptySet) revokes EVERY live share (total revocation)") {
            runTest {
                shareDao.upsert(share("s1", "c1", "u1"))
                shareDao.upsert(share("s2", "c1", "u2"))
                shareDao.liveIds().toSet() shouldBe setOf("s1", "s2")

                shareDao.tombstoneNotIn(emptySet(), now = 777L)

                shareDao.liveIds().shouldBeEmpty()
                shareDao.getById("s1") shouldBe null
                shareDao.getById("s2") shouldBe null
            }
        }

        // ── CollectionBookDao: junction ───────────────────────────────────────

        test("findByKey returns the live junction row") {
            runTest {
                bookDao.upsert(member("c1", "b1"))
                bookDao.findByKey("c1", "b1") shouldNotBe null
            }
        }

        test("tombstone hides a junction row from observeBookIds") {
            runTest {
                bookDao.upsert(member("c1", "b1"))
                bookDao.observeBookIds("c1").test {
                    awaitItem() shouldContainExactly listOf("b1")
                    bookDao.tombstone("c1", "b1", deletedAt = 50L, revision = 2L)
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── CollectionShareDao ────────────────────────────────────────────────

        test("observeForCollection returns live shares and excludes revoked ones") {
            runTest {
                shareDao.upsert(share("s1", "c1", "u1"))
                shareDao.upsert(share("s2", "c1", "u2", deletedAt = 5L))
                shareDao.observeForCollection("c1").test {
                    awaitItem().map { it.id } shouldContainExactly listOf("s1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("softDelete revokes a share reactively") {
            runTest {
                shareDao.upsert(share("s1", "c1", "u1"))
                shareDao.observeForCollection("c1").test {
                    awaitItem().map { it.id } shouldContainExactly listOf("s1")
                    shareDao.softDelete("s1", deletedAt = 9L, revision = 2L)
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun collection(
    id: String,
    name: String,
    libraryId: String = "lib1",
    ownerId: String = "owner1",
    deletedAt: Long? = null,
) = CollectionEntity(
    id = id,
    libraryId = libraryId,
    ownerId = ownerId,
    name = name,
    isInbox = false,
    revision = 1L,
    deletedAt = deletedAt,
    updatedAt = 100L,
)

private fun member(
    collectionId: String,
    bookId: String,
    createdAt: Long = 1L,
    deletedAt: Long? = null,
) = CollectionBookEntity(
    collectionId = collectionId,
    bookId = bookId,
    createdAt = createdAt,
    revision = 1L,
    deletedAt = deletedAt,
)

private fun share(
    id: String,
    collectionId: String,
    sharedWithUserId: String,
    deletedAt: Long? = null,
) = CollectionShareEntity(
    id = id,
    collectionId = collectionId,
    sharedWithUserId = sharedWithUserId,
    sharedByUserId = "owner1",
    permission = "read",
    revision = 1L,
    deletedAt = deletedAt,
    updatedAt = 100L,
)
