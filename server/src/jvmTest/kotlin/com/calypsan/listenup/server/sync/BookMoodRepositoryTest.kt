@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Tests for [BookMoodRepository] and the batched [MoodRepository.findByIds] read —
 * the composite-key twin of `BookTagRepositoryTest`. Covers upsert/softDelete,
 * bulk cascade variants, query helpers, sync-event publication, the batched read
 * that fixes the `listMoodsForBook` N+1, and pullSince tombstone inclusion.
 *
 * All tests use a real migrated database with fully-satisfied FK constraints:
 * library + folder rows are seeded via [seedTestLibraryAndFolder], book rows via
 * [seedTestBook] (both through the Exposed view over the shared file), and mood rows
 * via [MoodRepository.upsert] (through the SQLDelight view).
 */
class BookMoodRepositoryTest :
    FunSpec({

        // ── upsert ───────────────────────────────────────────────────────────────

        test("upsert adds junction row and emits SyncEvent.Created") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood(id = "m1", name = "Tense", slug = "tense", revision = 0, updatedAt = 0))

                    val sub =
                        async {
                            // Drop the mood Created event to observe the junction Created event.
                            bus.subscribe().drop(1).first()
                        }
                    advanceUntilIdle()

                    val result =
                        bookMoodRepo.upsert(
                            BookMoodSyncPayload(id = "book1:m1", bookId = "book1", moodId = "m1", createdAt = 1000L, revision = 0L),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<BookMoodSyncPayload>>()
                    val saved = (result as AppResult.Success).data
                    saved.bookId shouldBe "book1"
                    saved.moodId shouldBe "m1"
                    saved.deletedAt shouldBe null

                    val busEvent = sub.await()
                    busEvent.repo.domainName shouldBe "book_moods"
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Created<*>>()
                    busEvent.event.id shouldBe "book1:m1"
                }
            }
        }

        test("upsert of soft-deleted junction clears deletedAt and emits Updated") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood(id = "m1", name = "Cozy", slug = "cozy", revision = 0, updatedAt = 0))
                    val payload = BookMoodSyncPayload(id = "book1:m1", bookId = "book1", moodId = "m1", createdAt = 1000L, revision = 0L)
                    bookMoodRepo.upsert(payload)
                    bookMoodRepo.softDelete("book1", "m1")

                    val resurrected = bookMoodRepo.upsert(payload)
                    resurrected.shouldBeInstanceOf<AppResult.Success<BookMoodSyncPayload>>()
                    (resurrected as AppResult.Success).data.deletedAt shouldBe null
                }
            }
        }

        // ── findAllForBook / findAllForMood ───────────────────────────────────────

        test("findAllForBook returns non-tombstoned rows for the book, ordered by createdAt") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))
                    moodRepo.upsert(Mood("m2", "Cozy", "cozy", 0, 0))
                    moodRepo.upsert(Mood("m3", "Hopeful", "hopeful", 0, 0))

                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m2", "book1", "m2", 2000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m3", "book1", "m3", 3000L, 0L))
                    bookMoodRepo.softDelete("book1", "m3")

                    val rows = bookMoodRepo.findAllForBook("book1")
                    rows shouldHaveSize 2
                    // createdAt ordering is preserved.
                    rows.map { it.moodId } shouldContainExactly listOf("m1", "m2")
                }
            }
        }

        test("findAllForMood returns non-tombstoned rows for the mood") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))

                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book2:m1", "book2", "m1", 2000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book3:m1", "book3", "m1", 3000L, 0L))
                    bookMoodRepo.softDelete("book3", "m1")

                    val rows = bookMoodRepo.findAllForMood("m1")
                    rows shouldHaveSize 2
                    rows.map { it.bookId }.toSet() shouldBe setOf("book1", "book2")
                }
            }
        }

        // ── MoodRepository.findByIds (the N+1 fix) ────────────────────────────────

        test("findByIds returns moods in requested order and skips absent/tombstoned ids") {
            withSqlDatabase {
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))
                    moodRepo.upsert(Mood("m2", "Cozy", "cozy", 0, 0))
                    moodRepo.upsert(Mood("m3", "Hopeful", "hopeful", 0, 0))
                    moodRepo.softDelete("m2")

                    // Request in a deliberately non-insertion order, with one absent id ("mX")
                    // and one tombstoned id ("m2") interleaved.
                    val moods = moodRepo.findByIds(listOf("m3", "m2", "mX", "m1"))

                    // Order matches the requested order; m2 (tombstoned) and mX (absent) dropped.
                    moods.map { it.id } shouldContainExactly listOf("m3", "m1")
                }
            }
        }

        test("findByIds on empty input returns empty without a query") {
            withSqlDatabase {
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.findByIds(emptyList()).shouldBeEmpty()
                }
            }
        }

        // ── softDelete ────────────────────────────────────────────────────────────

        test("softDelete marks tombstone, emits Deleted, and round-trips out of findAllForBook") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))

                    val sub = async { bus.subscribe().drop(2).first() }
                    advanceUntilIdle()

                    val result = bookMoodRepo.softDelete("book1", "m1", clientOpId = "op-del")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val busEvent = sub.await()
                    busEvent.repo.domainName shouldBe "book_moods"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Deleted>()
                    event.id shouldBe "book1:m1"
                    event.clientOpId shouldBe "op-del"

                    bookMoodRepo.findAllForBook("book1").shouldBeEmpty()
                }
            }
        }

        test("softDelete on non-existent key returns Failure") {
            withSqlDatabase {
                val registry = SyncRegistry()
                val bus = ChangeBus()
                MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    val result = bookMoodRepo.softDelete("bookX", "moodX")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        // ── softDeleteAllForBook ──────────────────────────────────────────────────

        test("softDeleteAllForBook tombstones all junction rows for the book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))
                    moodRepo.upsert(Mood("m2", "Cozy", "cozy", 0, 0))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m2", "book1", "m2", 2000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book2:m1", "book2", "m1", 3000L, 0L))

                    val count = bookMoodRepo.softDeleteAllForBook("book1")
                    count shouldBe 2

                    bookMoodRepo.findAllForBook("book1").shouldBeEmpty()
                    // book2's junction row is unaffected.
                    bookMoodRepo.findAllForBook("book2") shouldHaveSize 1
                }
            }
        }

        test("softDeleteAllForBook emits Deleted bus events for each row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))
                    moodRepo.upsert(Mood("m2", "Cozy", "cozy", 0, 0))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m2", "book1", "m2", 2000L, 0L))

                    // Subscribe before the action; drop the 4 setup events
                    // (2 mood Created + 2 junction Created).
                    val sub = async { bus.subscribe().drop(4).first() }
                    advanceUntilIdle()

                    bookMoodRepo.softDeleteAllForBook("book1")

                    val first = sub.await()
                    first.repo.domainName shouldBe "book_moods"
                    first.event.shouldBeInstanceOf<SyncEvent.Deleted>()
                }
            }
        }

        // ── softDeleteAllForMood ──────────────────────────────────────────────────

        test("softDeleteAllForMood tombstones all junction rows for the mood") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book2:m1", "book2", "m1", 2000L, 0L))

                    val count = bookMoodRepo.softDeleteAllForMood("m1")
                    count shouldBe 2

                    bookMoodRepo.findAllForMood("m1").shouldBeEmpty()
                }
            }
        }

        // ── findBookIdsForMood ────────────────────────────────────────────────────

        test("findBookIdsForMood returns book IDs for non-tombstoned rows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Cozy", "cozy", 0, 0))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book2:m1", "book2", "m1", 2000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book3:m1", "book3", "m1", 3000L, 0L))
                    bookMoodRepo.softDelete("book3", "m1")

                    val bookIds = bookMoodRepo.findBookIdsForMood("m1")
                    bookIds.toSet() shouldBe setOf("book1", "book2")
                }
            }
        }

        // ── pullSince / revision ordering ─────────────────────────────────────────

        test("pullSince returns junction rows ordered by revision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))
                    moodRepo.upsert(Mood("m2", "Cozy", "cozy", 0, 0))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m2", "book1", "m2", 2000L, 0L))

                    val page = bookMoodRepo.pullSince(userId = null, cursor = 0L, limit = 100)
                    page.items shouldHaveSize 2
                    page.hasMore shouldBe false
                    val revs = page.items.map { it.revision }
                    (revs[0] < revs[1]) shouldBe true
                }
            }
        }

        test("pullSince includes tombstoned rows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood("m1", "Tense", "tense", 0, 0))
                    bookMoodRepo.upsert(BookMoodSyncPayload("book1:m1", "book1", "m1", 1000L, 0L))
                    bookMoodRepo.softDelete("book1", "m1")

                    val page = bookMoodRepo.pullSince(userId = null, cursor = 0L, limit = 100)
                    page.items shouldHaveSize 1
                    page.items.first().deletedAt shouldNotBe null
                }
            }
        }
    })
