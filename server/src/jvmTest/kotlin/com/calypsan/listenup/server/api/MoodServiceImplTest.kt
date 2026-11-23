package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.moodsOnBook
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [MoodServiceImpl].
 *
 * Uses a real in-memory migrated SQLite database + real repositories; no mocks.
 */
class MoodServiceImplTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun makeService(
            sql: ListenUpDatabase,
            driver: app.cash.sqldelight.db.SqlDriver,
        ): MoodServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
            val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry, driver = driver)
            return MoodServiceImpl(
                moodRepository = moodRepo,
                bookMoodRepository = bookMoodRepo,
                sql = sql,
                accessPolicy = BookAccessPolicy(sql, driver),
                clock = fixedClock,
                principal = rootPrincipal(),
            )
        }

        // ── getMoodStats ─────────────────────────────────────────────────────

        test("getMoodStats returns EMPTY for a mood with no live junctions") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql, driver)
                    service.addMoodToBook(BookId("book1"), "Feel-Good")
                    val result = service.getMoodStats(MoodId("no-such-mood"))
                    result shouldBe AppResult.Success(FacetStats.EMPTY)
                }
            }
        }

        test("getMoodStats sums book count and duration over live junction books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.booksQueries.updateTotalDuration(total_duration = 1_000L, id = "book1")
                sql.booksQueries.updateTotalDuration(total_duration = 2_000L, id = "book2")
                runTest {
                    val service = makeService(sql, driver)
                    val addResult = service.addMoodToBook(BookId("book1"), "Feel-Good")
                    require(addResult is AppResult.Success)
                    val moodId = MoodId(addResult.data.id)
                    service.addMoodToBook(BookId("book2"), "Feel-Good")

                    val result = service.getMoodStats(moodId)
                    result shouldBe AppResult.Success(FacetStats(bookCount = 2, totalDurationMs = 3_000L))
                }
            }
        }

        test("getMoodStats excludes a book whose junction row was removed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.booksQueries.updateTotalDuration(total_duration = 1_000L, id = "book1")
                runTest {
                    val service = makeService(sql, driver)
                    val addResult = service.addMoodToBook(BookId("book1"), "Feel-Good")
                    require(addResult is AppResult.Success)
                    val moodId = MoodId(addResult.data.id)
                    service.removeMoodFromBook(BookId("book1"), moodId)

                    val result = service.getMoodStats(moodId)
                    result shouldBe AppResult.Success(FacetStats.EMPTY)
                }
            }
        }

        test("getMoodStats excludes a soft-deleted book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.booksQueries.updateTotalDuration(total_duration = 1_000L, id = "book1")
                runTest {
                    val service = makeService(sql, driver)
                    val addResult = service.addMoodToBook(BookId("book1"), "Feel-Good")
                    require(addResult is AppResult.Success)
                    val moodId = MoodId(addResult.data.id)

                    sql.booksQueries.softDeleteById(
                        id = "book1",
                        revision = 2L,
                        updated_at = 1_700_000_000_000L,
                        deleted_at = 1_700_000_000_000L,
                        client_op_id = null,
                    )

                    val result = service.getMoodStats(moodId)
                    result shouldBe AppResult.Success(FacetStats.EMPTY)
                }
            }
        }

        // ── addMoodToBook ──────────────────────────────────────────────────────

        test("addMoodToBook creates new mood and junction") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.addMoodToBook(BookId("book1"), "Tense")

                    require(result is AppResult.Success)
                    result.data.name shouldBe "Tense"
                    result.data.slug shouldBe "tense"
                }
            }
        }

        test("addMoodToBook rejects nonexistent book with BookNotFound") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.addMoodToBook(BookId("no-such-book"), "Tense")

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<MoodError.BookNotFound>()
                }
            }
        }

        test("addMoodToBook rejects empty name with InvalidName") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.addMoodToBook(BookId("book1"), "")

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<MoodError.InvalidName>()
                }
            }
        }

        test("addMoodToBook is idempotent — same name twice yields one junction") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql, driver)
                    service.addMoodToBook(BookId("book1"), "Feel-Good")
                    service.addMoodToBook(BookId("book1"), "Feel-Good")

                    val moods = moodsOnBook("book1")
                    moods shouldHaveSize 1
                }
            }
        }

        test("addMoodToBook reuses existing mood by slug") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val service = makeService(sql, driver)
                    val r1 = service.addMoodToBook(BookId("book1"), "Feel-Good")
                    val r2 = service.addMoodToBook(BookId("book2"), "feel-good")

                    require(r1 is AppResult.Success)
                    require(r2 is AppResult.Success)
                    // Same mood id — existing mood was reused.
                    r1.data.id shouldBe r2.data.id
                }
            }
        }

        // ── removeMoodFromBook ─────────────────────────────────────────────────

        test("removeMoodFromBook tombstones junction row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql, driver)
                    val addResult = service.addMoodToBook(BookId("book1"), "Feel-Good")
                    require(addResult is AppResult.Success)
                    val moodId = MoodId(addResult.data.id)

                    val removeResult = service.removeMoodFromBook(BookId("book1"), moodId)
                    removeResult shouldBe AppResult.Success(Unit)

                    val moods = moodsOnBook("book1")
                    moods.shouldBeEmpty()
                }
            }
        }
    })
