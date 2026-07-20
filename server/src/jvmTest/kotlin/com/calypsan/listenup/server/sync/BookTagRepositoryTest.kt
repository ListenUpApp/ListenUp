@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests for [BookTagRepository]: composite-key upsert/softDelete, bulk cascade
 * variants, query helpers, and sync-event publication.
 *
 * All tests use a real migrated database with fully-satisfied FK constraints:
 * library + folder rows are seeded via [seedTestLibraryAndFolder], book rows via
 * [seedTestBook] (both through the Exposed view over the shared file), and tag rows
 * via [TagRepository.upsert] (through the SQLDelight view).
 */
class BookTagRepositoryTest :
    FunSpec({

        // ── upsert ───────────────────────────────────────────────────────────────

        test("upsert adds junction row and emits SyncEvent.Created") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag(id = "tag1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))

                    val sub =
                        async {
                            // Drop the tag Created event to observe the junction Created event
                            bus.subscribe().drop(1).first()
                        }
                    advanceUntilIdle()

                    val result =
                        bookTagRepo.upsert(
                            BookTagSyncPayload(id = "book1:tag1", bookId = "book1", tagId = "tag1", createdAt = 1000L, revision = 0L),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<BookTagSyncPayload>>()
                    val saved = (result as AppResult.Success).data
                    saved.bookId shouldBe "book1"
                    saved.tagId shouldBe "tag1"
                    saved.deletedAt shouldBe null

                    val busEvent = sub.await()
                    busEvent.repo.domainName shouldBe "book_tags"
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Created<*>>()
                    busEvent.event.id shouldBe "book1:tag1"
                }
            }
        }

        test("upsert of soft-deleted junction clears deletedAt and emits Updated") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag(id = "tag1", name = "Fantasy", slug = "fantasy", revision = 0, updatedAt = 0))
                    val payload = BookTagSyncPayload(id = "book1:tag1", bookId = "book1", tagId = "tag1", createdAt = 1000L, revision = 0L)
                    bookTagRepo.upsert(payload)
                    bookTagRepo.softDelete("book1", "tag1")

                    val resurrected = bookTagRepo.upsert(payload)
                    resurrected.shouldBeInstanceOf<AppResult.Success<BookTagSyncPayload>>()
                    (resurrected as AppResult.Success).data.deletedAt shouldBe null
                }
            }
        }

        // ── findAllForBook / findAllForTag ────────────────────────────────────────

        test("findAllForBook returns non-tombstoned rows for the book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))
                    tagRepo.upsert(Tag("t2", "Fantasy", "fantasy", 0, 0))
                    tagRepo.upsert(Tag("t3", "Mystery", "mystery", 0, 0))

                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t2", "book1", "t2", 2000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t3", "book1", "t3", 3000L, 0L))
                    bookTagRepo.softDelete("book1", "t3")

                    val rows = bookTagRepo.findAllForBook("book1")
                    rows shouldHaveSize 2
                    rows.map { it.tagId }.toSet() shouldBe setOf("t1", "t2")
                }
            }
        }

        test("findAllForTag returns non-tombstoned rows for the tag") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))

                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book2:t1", "book2", "t1", 2000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book3:t1", "book3", "t1", 3000L, 0L))
                    bookTagRepo.softDelete("book3", "t1")

                    val rows = bookTagRepo.findAllForTag("t1")
                    rows shouldHaveSize 2
                    rows.map { it.bookId }.toSet() shouldBe setOf("book1", "book2")
                }
            }
        }

        test("findAllForBook excludes rows for other books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book2:t1", "book2", "t1", 2000L, 0L))

                    val rows = bookTagRepo.findAllForBook("book1")
                    rows shouldHaveSize 1
                    rows.first().bookId shouldBe "book1"
                }
            }
        }

        // ── softDelete ────────────────────────────────────────────────────────────

        test("softDelete marks tombstone and emits SyncEvent.Deleted") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))

                    val sub = async { bus.subscribe().drop(2).first() }
                    advanceUntilIdle()

                    val result = bookTagRepo.softDelete("book1", "t1", clientOpId = "op-del")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val busEvent = sub.await()
                    busEvent.repo.domainName shouldBe "book_tags"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Deleted>()
                    event.id shouldBe "book1:t1"
                    event.clientOpId shouldBe "op-del"
                }
            }
        }

        test("softDelete on non-existent key returns Failure") {
            withSqlDatabase {
                val registry = SyncRegistry()
                val bus = ChangeBus()
                TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    val result = bookTagRepo.softDelete("bookX", "tagX")
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
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))
                    tagRepo.upsert(Tag("t2", "Fantasy", "fantasy", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t2", "book1", "t2", 2000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book2:t1", "book2", "t1", 3000L, 0L))

                    val count = bookTagRepo.softDeleteAllForBook("book1")
                    count shouldBe 2

                    bookTagRepo.findAllForBook("book1").shouldBeEmpty()
                    // book2's junction row is unaffected
                    bookTagRepo.findAllForBook("book2") shouldHaveSize 1
                }
            }
        }

        test("softDeleteAllForBook emits Deleted bus events for each row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))
                    tagRepo.upsert(Tag("t2", "Fantasy", "fantasy", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t2", "book1", "t2", 2000L, 0L))

                    // Subscribe before the action; drop the 4 setup events
                    // (2 tag Created + 2 junction Created)
                    val sub = async { bus.subscribe().drop(4).first() }
                    advanceUntilIdle()

                    bookTagRepo.softDeleteAllForBook("book1")

                    // At least one Deleted event was published for book_tags domain
                    val first = sub.await()
                    first.repo.domainName shouldBe "book_tags"
                    first.event.shouldBeInstanceOf<SyncEvent.Deleted>()
                }
            }
        }

        // ── softDeleteAllForTag ───────────────────────────────────────────────────

        test("softDeleteAllForTag tombstones all junction rows for the tag") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book2:t1", "book2", "t1", 2000L, 0L))

                    val count = bookTagRepo.softDeleteAllForTag("t1")
                    count shouldBe 2

                    bookTagRepo.findAllForTag("t1").shouldBeEmpty()
                }
            }
        }

        // ── findBookIdsForTag ─────────────────────────────────────────────────────

        test("findBookIdsForTag returns book IDs for non-tombstoned rows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Fantasy", "fantasy", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book2:t1", "book2", "t1", 2000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book3:t1", "book3", "t1", 3000L, 0L))
                    bookTagRepo.softDelete("book3", "t1")

                    val bookIds = bookTagRepo.findBookIdsForTag("t1")
                    bookIds.toSet() shouldBe setOf("book1", "book2")
                }
            }
        }

        // ── reviveAllForBooks (folder re-add cascade) ─────────────────────────────

        test("reviveAllForBooks revives only junctions tombstoned at or after the cascade floor") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val registry = SyncRegistry()
                val bus = ChangeBus()
                // Deterministic time so the manual removal and the folder-removal cascade land at
                // distinct, ordered instants.
                val clock = MutableClock(Instant.fromEpochMilliseconds(1_000L))
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry, clock = clock)

                runTest {
                    tagRepo.upsert(Tag("manual", "Manual", "manual", 0, 0))
                    tagRepo.upsert(Tag("folder", "Folder", "folder", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:manual", "book1", "manual", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:folder", "book1", "folder", 1000L, 0L))

                    // The user removes the "manual" tag BEFORE the folder is removed — an older tombstone.
                    clock.set(Instant.fromEpochMilliseconds(5_000L))
                    bookTagRepo.softDelete("book1", "manual")

                    // The folder-removal cascade tombstones the "folder" tag at the removal instant.
                    val folderRemovedAt = 10_000L
                    clock.set(Instant.fromEpochMilliseconds(folderRemovedAt))
                    bookTagRepo.softDelete("book1", "folder")

                    // Re-add revives only junctions tombstoned at/after the folder-removal floor.
                    val revived = bookTagRepo.reviveAllForBooks(listOf("book1"), cascadeFloor = folderRemovedAt)
                    revived shouldBe 1

                    // The folder-removal tag is live again; the manually-removed tag stays dead.
                    bookTagRepo.findAllForBook("book1").map { it.tagId } shouldBe listOf("folder")
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
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))
                    tagRepo.upsert(Tag("t2", "Fantasy", "fantasy", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t2", "book1", "t2", 2000L, 0L))

                    val page = bookTagRepo.pullSince(userId = null, cursor = 0L, limit = 100)
                    page.items shouldHaveSize 2
                    page.hasMore shouldBe false
                    // revisions must be strictly ascending
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
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag("t1", "Sci-Fi", "sci-fi", 0, 0))
                    bookTagRepo.upsert(BookTagSyncPayload("book1:t1", "book1", "t1", 1000L, 0L))
                    bookTagRepo.softDelete("book1", "t1")

                    val page = bookTagRepo.pullSince(userId = null, cursor = 0L, limit = 100)
                    page.items shouldHaveSize 1
                    page.items.first().deletedAt shouldNotBe null
                }
            }
        }
    })

/** A mutable [Clock] for tests that need to advance time deterministically. */
private class MutableClock(
    private var time: Instant,
) : Clock {
    override fun now(): Instant = time

    fun set(newTime: Instant) {
        time = newTime
    }
}
