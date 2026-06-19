@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Behaviour for [ActivityRepository]: the append-only `record` write and the keyset-paginated,
 * most-recent-first `page` read that backs the activity feed.
 */
class ActivityRepositoryTest :
    FunSpec({

        test("record returns a non-blank id and inserts a retrievable row") {
            withInMemoryDatabase {
                val repo = ActivityRepository(db = this, clock = FixedClock(Instant.fromEpochMilliseconds(1000L)))
                runTest {
                    val id = repo.record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "book-1")
                    id shouldNotBe ""

                    val rows = repo.page(before = null, limit = 10)
                    rows.size shouldBe 1
                    val row = rows.single()
                    row.id shouldBe id
                    row.userId shouldBe "u1"
                    row.type shouldBe ActivityType.STARTED_BOOK
                    row.createdAt shouldBe 1000L
                    row.bookId shouldBe "book-1"
                }
            }
        }

        test("page returns rows most-recent-first") {
            withInMemoryDatabase {
                runTest {
                    repoAt(10L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "a")
                    repoAt(20L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "b")
                    repoAt(30L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "c")

                    val rows = repoAt(0L).page(before = null, limit = 10)
                    rows.map { it.bookId } shouldBe listOf("c", "b", "a")
                }
            }
        }

        test("page with a before cursor returns only strictly-older rows") {
            withInMemoryDatabase {
                runTest {
                    repoAt(10L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "a")
                    repoAt(20L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "b")
                    repoAt(30L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "c")

                    val rows = repoAt(0L).page(before = 30L, limit = 10)
                    rows.map { it.bookId } shouldBe listOf("b", "a")
                }
            }
        }

        test("a book-bearing record and a non-book record both round-trip with their fields intact") {
            withInMemoryDatabase {
                runTest {
                    repoAt(10L).record(
                        userId = "u1",
                        type = ActivityType.FINISHED_BOOK,
                        bookId = "book-1",
                        isReread = true,
                        durationMs = 42_000L,
                    )
                    repoAt(20L).record(
                        userId = "u2",
                        type = ActivityType.SHELF_CREATED,
                        shelfId = "shelf-1",
                        shelfName = "Winter Reads",
                    )

                    val rows = repoAt(0L).page(before = null, limit = 10)

                    val shelf = rows.first { it.type == ActivityType.SHELF_CREATED }
                    shelf.userId shouldBe "u2"
                    shelf.bookId shouldBe null
                    shelf.shelfId shouldBe "shelf-1"
                    shelf.shelfName shouldBe "Winter Reads"

                    val finished = rows.first { it.type == ActivityType.FINISHED_BOOK }
                    finished.userId shouldBe "u1"
                    finished.bookId shouldBe "book-1"
                    finished.isReread shouldBe true
                    finished.durationMs shouldBe 42_000L
                    finished.shelfId shouldBe null
                }
            }
        }
    })

private fun Database.repoAt(millis: Long) = ActivityRepository(db = this, clock = FixedClock(Instant.fromEpochMilliseconds(millis)))
