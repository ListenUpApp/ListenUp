@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Behaviour for the activity feed's read path: rows written through the syncable
 * [ActivityRecorder] are retrievable via [ActivityRepository]'s keyset-paginated,
 * most-recent-first `page` read.
 */
class ActivityRepositoryTest :
    FunSpec({

        test("a recorded activity inserts a retrievable row with its fields intact") {
            withSqlDatabase {
                runTest {
                    recorderAt(1000L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "book-1")

                    val rows = ActivityRepository(db = sql).page(before = null, limit = 10)
                    rows.size shouldBe 1
                    val row = rows.single()
                    row.id shouldNotBe ""
                    row.userId shouldBe "u1"
                    row.type shouldBe ActivityType.STARTED_BOOK
                    row.createdAt shouldBe 1000L
                    row.bookId shouldBe "book-1"
                }
            }
        }

        test("page returns rows most-recent-first") {
            withSqlDatabase {
                runTest {
                    recorderAt(10L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "a")
                    recorderAt(20L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "b")
                    recorderAt(30L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "c")

                    val rows = ActivityRepository(db = sql).page(before = null, limit = 10)
                    rows.map { it.bookId } shouldBe listOf("c", "b", "a")
                }
            }
        }

        test("page with a before cursor returns only strictly-older rows") {
            withSqlDatabase {
                runTest {
                    recorderAt(10L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "a")
                    recorderAt(20L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "b")
                    recorderAt(30L).record(userId = "u1", type = ActivityType.STARTED_BOOK, bookId = "c")

                    val rows = ActivityRepository(db = sql).page(before = 30L, limit = 10)
                    rows.map { it.bookId } shouldBe listOf("b", "a")
                }
            }
        }

        test("a book-bearing record and a non-book record both round-trip with their fields intact") {
            withSqlDatabase {
                runTest {
                    recorderAt(10L).record(
                        userId = "u1",
                        type = ActivityType.FINISHED_BOOK,
                        bookId = "book-1",
                        isReread = true,
                        durationMs = 42_000L,
                    )
                    recorderAt(20L).record(
                        userId = "u2",
                        type = ActivityType.SHELF_CREATED,
                        shelfId = "shelf-1",
                        shelfName = "Winter Reads",
                    )

                    val rows = ActivityRepository(db = sql).page(before = null, limit = 10)

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

/** A syncable [ActivityRecorder] over the test db whose clock is pinned to [millis]. */
private fun SqlTestDatabases.recorderAt(millis: Long): ActivityRecorder =
    ActivityRecorder(
        syncRepo = ActivitySyncRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver),
        clock = FixedClock(Instant.fromEpochMilliseconds(millis)),
    )
