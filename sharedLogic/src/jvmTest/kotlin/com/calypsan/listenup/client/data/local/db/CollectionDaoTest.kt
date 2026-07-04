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

        // ── Access-prune primitive: tombstoneByIds tombstones exactly the given rows ──
        //
        // The composed handler computes the doomed set (`liveIds - accessible`) and tombstones it in
        // bounded chunks. These pin the DAO leg: tombstoneByIds evicts exactly the ids it is handed,
        // so a total revocation (handler passes every live id) evicts everything, while an empty
        // chunk (accessible = everything) touches nothing.

        test("tombstoneByIds tombstones exactly the given collections, leaving the rest live") {
            runTest {
                collectionDao.upsert(collection("c1", "Alpha"))
                collectionDao.upsert(collection("c2", "Beta"))
                collectionDao.upsert(collection("c3", "Gamma"))
                collectionDao.liveIds().toSet() shouldBe setOf("c1", "c2", "c3")

                collectionDao.tombstoneByIds(listOf("c1", "c2"), now = 777L)

                collectionDao.liveIds() shouldBe listOf("c3")
                collectionDao.getById("c1") shouldBe null
                collectionDao.getById("c2") shouldBe null
                collectionDao.getById("c3") shouldNotBe null
            }
        }

        test("tombstoneByIds tombstones exactly the given junction rows, leaving the rest live") {
            runTest {
                bookDao.upsert(member("c1", "b1"))
                bookDao.upsert(member("c1", "b2"))
                bookDao.upsert(member("c1", "b3"))
                bookDao.liveSyntheticIds().toSet() shouldBe setOf("c1:b1", "c1:b2", "c1:b3")

                bookDao.tombstoneByIds(listOf("c1:b1", "c1:b2"), now = 777L)

                bookDao.liveSyntheticIds() shouldBe listOf("c1:b3")
                bookDao.findByKey("c1", "b1")!!.deletedAt shouldNotBe null
                bookDao.findByKey("c1", "b2")!!.deletedAt shouldNotBe null
                bookDao.findByKey("c1", "b3")!!.deletedAt shouldBe null
            }
        }

        test("tombstoneByIds revokes exactly the given shares, leaving the rest live") {
            runTest {
                shareDao.upsert(share("s1", "c1", "u1"))
                shareDao.upsert(share("s2", "c1", "u2"))
                shareDao.upsert(share("s3", "c1", "u3"))
                shareDao.liveIds().toSet() shouldBe setOf("s1", "s2", "s3")

                shareDao.tombstoneByIds(listOf("s1", "s2"), now = 777L)

                shareDao.liveIds() shouldBe listOf("s3")
                shareDao.getById("s1") shouldBe null
                shareDao.getById("s2") shouldBe null
                shareDao.getById("s3") shouldNotBe null
            }
        }

        test("tombstoneByIds(emptyList) tombstones nothing (accessible = everything)") {
            runTest {
                collectionDao.upsert(collection("c1", "Alpha"))
                collectionDao.upsert(collection("c2", "Beta"))

                collectionDao.tombstoneByIds(emptyList(), now = 777L)

                collectionDao.liveIds().toSet() shouldBe setOf("c1", "c2")
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
