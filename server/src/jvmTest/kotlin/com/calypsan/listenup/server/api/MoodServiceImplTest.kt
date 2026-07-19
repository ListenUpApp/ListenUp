package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [MoodServiceImpl].
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories; no mocks.
 * Moods are not part of `book_search` FTS, so there is no reindexer to wire.
 */
class MoodServiceImplTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun makeService(sql: ListenUpDatabase): MoodServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
            val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)
            return MoodServiceImpl(moodRepo, bookMoodRepo, sql, fixedClock, principal = rootPrincipal())
        }

        // ── listMoods ────────────────────────────────────────────────────────

        test("listMoods returns empty list when no moods exist") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql)
                    val result = service.listMoods()
                    result shouldBe AppResult.Success(emptyList())
                }
            }
        }

        test("listMoods returns moods with correct bookCount") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val service = makeService(sql)
                    service.addMoodToBook(BookId("book1"), "Feel-Good")
                    service.addMoodToBook(BookId("book2"), "Feel-Good")

                    val result = service.listMoods()
                    require(result is AppResult.Success)
                    result.data shouldHaveSize 1
                    result.data.first().name shouldBe "Feel-Good"
                    result.data.first().bookCount shouldBe 2L
                }
            }
        }

        // ── getMoodBySlug ─────────────────────────────────────────────────────

        test("getMoodBySlug returns null for nonexistent slug") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql)
                    val result = service.getMoodBySlug("nonexistent")
                    result shouldBe AppResult.Success(null)
                }
            }
        }

        test("getMoodBySlug returns mood with bookCount when slug matches") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql)
                    service.addMoodToBook(BookId("book1"), "Feel-Good")

                    val result = service.getMoodBySlug("feel-good")
                    require(result is AppResult.Success)
                    result.data.shouldNotBeNull()
                    result.data!!.name shouldBe "Feel-Good"
                    result.data!!.bookCount shouldBe 1L
                }
            }
        }

        // ── getMoodStats ─────────────────────────────────────────────────────

        test("getMoodStats returns EMPTY for a mood with no live junctions") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql)
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
                    val service = makeService(sql)
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
                    val service = makeService(sql)
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
                    val service = makeService(sql)
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
                    val service = makeService(sql)
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
                    val service = makeService(sql)
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
                    val service = makeService(sql)
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
                    val service = makeService(sql)
                    service.addMoodToBook(BookId("book1"), "Feel-Good")
                    service.addMoodToBook(BookId("book1"), "Feel-Good")

                    val moods = service.listMoodsForBook(BookId("book1"))
                    require(moods is AppResult.Success)
                    moods.data shouldHaveSize 1
                }
            }
        }

        test("addMoodToBook reuses existing mood by slug") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val service = makeService(sql)
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
                    val service = makeService(sql)
                    val addResult = service.addMoodToBook(BookId("book1"), "Feel-Good")
                    require(addResult is AppResult.Success)
                    val moodId = MoodId(addResult.data.id)

                    val removeResult = service.removeMoodFromBook(BookId("book1"), moodId)
                    removeResult shouldBe AppResult.Success(Unit)

                    val moods = service.listMoodsForBook(BookId("book1"))
                    require(moods is AppResult.Success)
                    moods.data.shouldBeEmpty()
                }
            }
        }

        // ── renameMood ─────────────────────────────────────────────────────────

        test("renameMood updates name but preserves slug") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql)
                    val addResult = service.addMoodToBook(BookId("book1"), "Feel-Good")
                    require(addResult is AppResult.Success)
                    val moodId = MoodId(addResult.data.id)
                    val originalSlug = addResult.data.slug

                    val renameResult = service.renameMood(moodId, "Feelgood Vibes")
                    require(renameResult is AppResult.Success)
                    renameResult.data.name shouldBe "Feelgood Vibes"
                    renameResult.data.slug shouldBe originalSlug
                }
            }
        }

        test("renameMood returns NotFound for missing mood") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql)
                    val result = service.renameMood(MoodId("no-such-mood"), "New Name")

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<MoodError.NotFound>()
                }
            }
        }

        // ── deleteMood ─────────────────────────────────────────────────────────

        test("deleteMood tombstones mood and all junctions") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val service = makeService(sql)
                    service.addMoodToBook(BookId("book1"), "Feel-Good")
                    service.addMoodToBook(BookId("book2"), "Feel-Good")

                    val moodId =
                        run {
                            val r = service.getMoodBySlug("feel-good")
                            require(r is AppResult.Success)
                            MoodId(r.data!!.id.value)
                        }

                    val deleteResult = service.deleteMood(moodId)
                    deleteResult shouldBe AppResult.Success(Unit)

                    // Mood no longer visible via slug.
                    service.getMoodBySlug("feel-good") shouldBe AppResult.Success(null)

                    val book1Moods = service.listMoodsForBook(BookId("book1"))
                    require(book1Moods is AppResult.Success)
                    book1Moods.data.shouldBeEmpty()

                    val book2Moods = service.listMoodsForBook(BookId("book2"))
                    require(book2Moods is AppResult.Success)
                    book2Moods.data.shouldBeEmpty()
                }
            }
        }

        test("deleteMood returns NotFound for missing mood") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql)
                    val result = service.deleteMood(MoodId("no-such-mood"))

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<MoodError.NotFound>()
                }
            }
        }

        // ── listMoodsForBook ───────────────────────────────────────────────────

        test("listMoodsForBook returns BookNotFound for missing book") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql)
                    val result = service.listMoodsForBook(BookId("no-such-book"))

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<MoodError.BookNotFound>()
                }
            }
        }

        test("listMoodsForBook returns empty list when book has no moods") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql)
                    val result = service.listMoodsForBook(BookId("book1"))

                    result shouldBe AppResult.Success(emptyList())
                }
            }
        }

        // A book with several moods exercises the batched findByIds read (the N+1 fix):
        // one round-trip resolves every junction's mood rather than one findById per row.
        test("listMoodsForBook returns all moods for a book with multiple moods") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql)
                    service.addMoodToBook(BookId("book1"), "Feel-Good")
                    service.addMoodToBook(BookId("book1"), "Tense")
                    service.addMoodToBook(BookId("book1"), "Hopeful")

                    val result = service.listMoodsForBook(BookId("book1"))
                    require(result is AppResult.Success)
                    result.data.map { it.name } shouldContainExactlyInAnyOrder
                        listOf("Feel-Good", "Tense", "Hopeful")
                }
            }
        }

        // findByIds skips tombstoned moods: after a mood is deleted, its junction
        // tombstones too, so listMoodsForBook must not surface the dead mood.
        test("listMoodsForBook skips a deleted mood") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(sql)
                    service.addMoodToBook(BookId("book1"), "Feel-Good")
                    val tense = service.addMoodToBook(BookId("book1"), "Tense")
                    require(tense is AppResult.Success)

                    service.deleteMood(MoodId(tense.data.id))

                    val result = service.listMoodsForBook(BookId("book1"))
                    require(result is AppResult.Success)
                    result.data.map { it.name } shouldContainExactlyInAnyOrder listOf("Feel-Good")
                }
            }
        }
    })
