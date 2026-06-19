package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

class TagRepositoryPullSinceTest :
    FunSpec({

        test("pullSince(0) returns all rows ordered by revision asc") {
            withInMemoryDatabase {
                val db = this
                val repo = TagRepository(db, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                    repo.upsert(Tag("b", "beta", "beta", 0, 0))
                    repo.upsert(Tag("c", "gamma", "gamma", 0, 0))
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 100)
                    page.items shouldHaveSize 3
                    page.items.map { it.id } shouldBe listOf("a", "b", "c")
                    page.hasMore shouldBe false
                    page.nextCursor shouldBe page.items.last().revision
                }
            }
        }

        test("pullSince filters by cursor strictly greater than") {
            withInMemoryDatabase {
                val db = this
                val repo = TagRepository(db, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(Tag("a", "alpha", "alpha", 0, 0)) // revision 1
                    repo.upsert(Tag("b", "beta", "beta", 0, 0)) // revision 2
                    repo.upsert(Tag("c", "gamma", "gamma", 0, 0)) // revision 3
                    val page = repo.pullSince(userId = null, cursor = 1L, limit = 100)
                    page.items.map { it.id } shouldBe listOf("b", "c")
                }
            }
        }

        test("pullSince paginates with hasMore = true when limit reached") {
            withInMemoryDatabase {
                val db = this
                val repo = TagRepository(db, ChangeBus(), SyncRegistry())
                runTest {
                    (1..5).forEach { repo.upsert(Tag(id = "id-$it", name = "n$it", slug = "n$it", revision = 0, updatedAt = 0)) }
                    val first = repo.pullSince(userId = null, cursor = 0L, limit = 2)
                    first.items shouldHaveSize 2
                    first.hasMore shouldBe true
                    first.nextCursor!! shouldBe first.items.last().revision

                    val second = repo.pullSince(userId = null, cursor = first.nextCursor!!, limit = 2)
                    second.items shouldHaveSize 2
                    second.hasMore shouldBe true

                    val third = repo.pullSince(userId = null, cursor = second.nextCursor!!, limit = 2)
                    third.items shouldHaveSize 1
                    third.hasMore shouldBe false
                }
            }
        }

        test("pullSince includes soft-deleted rows with deletedAt populated") {
            withInMemoryDatabase {
                val db = this
                val repo = TagRepository(db, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(Tag("a", "alpha", "alpha", 0, 0))
                    repo.softDelete("a")
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 100)
                    page.items shouldHaveSize 1
                    page.items.first().deletedAt shouldNotBe null
                }
            }
        }

        test("empty pullSince returns Page(items=[], cursor=null, hasMore=false)") {
            withInMemoryDatabase {
                val db = this
                val repo = TagRepository(db, ChangeBus(), SyncRegistry())
                runTest {
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 100)
                    page.items shouldHaveSize 0
                    page.nextCursor shouldBe null
                    page.hasMore shouldBe false
                }
            }
        }
    })
