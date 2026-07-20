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
 * Covers the sync-substrate queries on [TagDao] (Room v22).
 *
 * All tests use an isolated in-memory database and clean up via [TagDao.deleteAll]
 * in [beforeEach] so each test starts from a blank slate.
 */
class TagDaoTest :
    FunSpec({
        val db = createInMemoryTestDatabase()
        val dao: TagDao = db.tagDao()

        beforeEach { dao.deleteAll() }
        afterSpec { db.close() }

        // ── findBySlug ────────────────────────────────────────────────────────

        test("findBySlug returns a live tag with the matching slug") {
            runTest {
                dao.upsert(tag("t1", "Sci-Fi", "sci-fi"))
                val result = dao.findBySlug("sci-fi")
                result shouldNotBe null
                result!!.id shouldBe "t1"
                result.name shouldBe "Sci-Fi"
            }
        }

        test("findBySlug returns null when no tag with that slug exists") {
            runTest {
                dao.upsert(tag("t1", "Fantasy", "fantasy"))
                dao.findBySlug("sci-fi") shouldBe null
            }
        }

        test("findBySlug excludes tombstoned tags") {
            runTest {
                dao.upsert(tag("t1", "Sci-Fi", "sci-fi", deletedAt = 100L))
                dao.findBySlug("sci-fi") shouldBe null
            }
        }

        // ── observeAll ────────────────────────────────────────────────────────

        test("observeAll emits all live tags ordered by name ascending") {
            runTest {
                dao.upsertAll(
                    listOf(
                        tag("t1", "Sci-Fi", "sci-fi"),
                        tag("t2", "Fantasy", "fantasy"),
                        tag("t3", "Thriller", "thriller"),
                    ),
                )
                dao.observeAll().test {
                    awaitItem().map { it.name } shouldContainExactly listOf("Fantasy", "Sci-Fi", "Thriller")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeAll excludes tombstoned tags") {
            runTest {
                dao.upsertAll(
                    listOf(
                        tag("t1", "Sci-Fi", "sci-fi"),
                        tag("t2", "Fantasy", "fantasy", deletedAt = 999L),
                    ),
                )
                dao.observeAll().test {
                    awaitItem().map { it.id } shouldContainExactly listOf("t1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeAll re-emits when a tombstone is applied via softDelete") {
            runTest {
                dao.upsert(tag("t1", "Sci-Fi", "sci-fi"))
                dao.observeAll().test {
                    awaitItem().map { it.id } shouldContainExactly listOf("t1")
                    dao.softDelete("t1", deletedAt = 500L, revision = 2L)
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── observeForBook ────────────────────────────────────────────────────

        test("observeForBook returns tags applied to the book via live junction rows") {
            runTest {
                dao.upsert(tag("t1", "Sci-Fi", "sci-fi"))
                dao.upsert(tag("t2", "Fantasy", "fantasy"))
                val bookTagDao = db.bookTagDao()
                bookTagDao.upsert(bookTag("b1", "t1"))
                bookTagDao.upsert(bookTag("b1", "t2"))

                dao.observeForBook("b1").test {
                    awaitItem().map { it.id }.toSet() shouldBe setOf("t1", "t2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeForBook excludes tags whose junction row is tombstoned") {
            runTest {
                dao.upsert(tag("t1", "Sci-Fi", "sci-fi"))
                dao.upsert(tag("t2", "Fantasy", "fantasy"))
                val bookTagDao = db.bookTagDao()
                bookTagDao.upsert(bookTag("b1", "t1"))
                bookTagDao.upsert(bookTag("b1", "t2", deletedAt = 200L))

                dao.observeForBook("b1").test {
                    awaitItem().map { it.id } shouldContainExactly listOf("t1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeForBook excludes tags whose tag row is tombstoned") {
            runTest {
                dao.upsert(tag("t1", "Sci-Fi", "sci-fi", deletedAt = 100L))
                dao.upsert(tag("t2", "Fantasy", "fantasy"))
                val bookTagDao = db.bookTagDao()
                bookTagDao.upsert(bookTag("b1", "t1"))
                bookTagDao.upsert(bookTag("b1", "t2"))

                dao.observeForBook("b1").test {
                    awaitItem().map { it.id } shouldContainExactly listOf("t2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeForBook returns tags ordered by name ascending") {
            runTest {
                dao.upsert(tag("t1", "Zombie", "zombie"))
                dao.upsert(tag("t2", "Alpha", "alpha"))
                val bookTagDao = db.bookTagDao()
                bookTagDao.upsert(bookTag("b1", "t1"))
                bookTagDao.upsert(bookTag("b1", "t2"))

                dao.observeForBook("b1").test {
                    awaitItem().map { it.name } shouldContainExactly listOf("Alpha", "Zombie")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun tag(
    id: String,
    name: String,
    slug: String,
    revision: Long = 1L,
    deletedAt: Long? = null,
    updatedAt: Long = 100L,
) = TagEntity(
    id = id,
    name = name,
    slug = slug,
    revision = revision,
    deletedAt = deletedAt,
    updatedAt = updatedAt,
)

private fun bookTag(
    bookId: String,
    tagId: String,
    createdAt: Long = 1L,
    revision: Long = 1L,
    deletedAt: Long? = null,
    syncId: String = "$bookId:$tagId",
) = BookTagEntity(
    bookId = bookId,
    tagId = tagId,
    syncId = syncId,
    createdAt = createdAt,
    revision = revision,
    deletedAt = deletedAt,
)
