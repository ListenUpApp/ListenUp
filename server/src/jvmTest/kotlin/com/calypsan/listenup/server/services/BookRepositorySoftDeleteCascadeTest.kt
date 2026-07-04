package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.test.runTest

/**
 * Verifies the cascade added to [BookRepository.softDelete]:
 * when a book is tombstoned, all of its `book_tags` junction rows are also
 * soft-deleted so clients receive per-row tombstones.
 */
class BookRepositorySoftDeleteCascadeTest :
    FunSpec({

        test("softDelete of a book tombstones all its book_tags junction rows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val bus = ChangeBus()
                    val syncRegistry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = syncRegistry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = syncRegistry)

                    val bookRepo =
                        BookRepository(
                            db = sql,
                            driver = driver,
                            bus = bus,
                            registry = syncRegistry,
                            contributorRepository = ContributorRepository(sql, bus, syncRegistry),
                            seriesRepository = SeriesRepository(sql, bus, syncRegistry),
                            genreRepository = GenreRepository(sql, bus, syncRegistry),
                            bookTagRepository = bookTagRepo,
                        )

                    // Seed a tag and attach it to book1.
                    tagRepo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(bookId = "book1", tagId = "t1", createdAt = 1000L, revision = 0L),
                    )

                    // Verify junction exists before delete.
                    val before = bookTagRepo.findAllForBook("book1")
                    check(before.size == 1) { "expected 1 junction before delete, got ${before.size}" }

                    // Soft-delete the book.
                    bookRepo.softDelete(BookId("book1"), clientOpId = null)

                    // Junction should now be tombstoned — findAllForBook returns live rows only.
                    val after = bookTagRepo.findAllForBook("book1")
                    after.shouldBeEmpty()
                }
            }
        }

        test("softDelete tombstones collection_books and book_moods junctions") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                // A regular (NORMAL) collection the book belongs to.
                val now = System.currentTimeMillis()
                sql.collectionsQueries.insert(
                    id = "c1",
                    library_id = "test-library",
                    owner_id = "owner1",
                    name = "Faves",
                    type = "NORMAL",
                    created_at = now,
                    updated_at = now,
                    revision = 0L,
                    deleted_at = null,
                    client_op_id = null,
                )
                runTest {
                    val bus = ChangeBus()
                    val syncRegistry = SyncRegistry()
                    val moodRepo = MoodRepository(db = sql, bus = bus, registry = syncRegistry)
                    val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = syncRegistry)
                    val collectionBookRepo =
                        CollectionBookRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)

                    val bookRepo =
                        BookRepository(
                            db = sql,
                            driver = driver,
                            bus = bus,
                            registry = syncRegistry,
                            contributorRepository = ContributorRepository(sql, bus, syncRegistry),
                            seriesRepository = SeriesRepository(sql, bus, syncRegistry),
                            genreRepository = GenreRepository(sql, bus, syncRegistry),
                            collectionBookRepository = collectionBookRepo,
                            bookMoodRepository = bookMoodRepo,
                        )

                    // Attach a mood and a collection membership to book1.
                    moodRepo.upsert(Mood(id = "m1", name = "Tense", slug = "tense", revision = 0, updatedAt = 0))
                    bookMoodRepo.upsert(
                        BookMoodSyncPayload(bookId = "book1", moodId = "m1", createdAt = 1000L, revision = 0L),
                    )
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(collectionId = "c1", bookId = "book1", createdAt = 1000L, revision = 0L),
                    )

                    check(collectionBookRepo.findBookIdsForCollection("c1") == listOf("book1"))
                    check(bookMoodRepo.findAllForBook("book1").size == 1)

                    bookRepo.softDelete(BookId("book1"), clientOpId = null)

                    // Both junctions are tombstoned — the dead book leaves the collection and its moods.
                    collectionBookRepo.findBookIdsForCollection("c1").shouldBeEmpty()
                    bookMoodRepo.findAllForBook("book1").shouldBeEmpty()
                }
            }
        }

        test("reviveByIds restores collection_books and book_moods memberships tombstoned by the removal") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val now = System.currentTimeMillis()
                sql.collectionsQueries.insert(
                    id = "c1",
                    library_id = "test-library",
                    owner_id = "owner1",
                    name = "Faves",
                    type = "NORMAL",
                    created_at = now,
                    updated_at = now,
                    revision = 0L,
                    deleted_at = null,
                    client_op_id = null,
                )
                runTest {
                    val bus = ChangeBus()
                    val syncRegistry = SyncRegistry()
                    val moodRepo = MoodRepository(db = sql, bus = bus, registry = syncRegistry)
                    val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = syncRegistry)
                    val collectionBookRepo =
                        CollectionBookRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)

                    val bookRepo =
                        BookRepository(
                            db = sql,
                            driver = driver,
                            bus = bus,
                            registry = syncRegistry,
                            contributorRepository = ContributorRepository(sql, bus, syncRegistry),
                            seriesRepository = SeriesRepository(sql, bus, syncRegistry),
                            genreRepository = GenreRepository(sql, bus, syncRegistry),
                            collectionBookRepository = collectionBookRepo,
                            bookMoodRepository = bookMoodRepo,
                        )

                    moodRepo.upsert(Mood(id = "m1", name = "Tense", slug = "tense", revision = 0, updatedAt = 0))
                    bookMoodRepo.upsert(
                        BookMoodSyncPayload(bookId = "book1", moodId = "m1", createdAt = 1000L, revision = 0L),
                    )
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(collectionId = "c1", bookId = "book1", createdAt = 1000L, revision = 0L),
                    )

                    // Remove (tombstones book + its junctions), then re-add (revive) with floor 0.
                    bookRepo.softDelete(BookId("book1"), clientOpId = null)
                    bookRepo.reviveByIds(listOf(BookId("book1")), cascadeFloor = 0L)

                    // Memberships return with the book.
                    collectionBookRepo.findBookIdsForCollection("c1").shouldContainExactly("book1")
                    bookMoodRepo.findAllForBook("book1").shouldHaveSize(1)
                }
            }
        }

        test("softDelete without bookTagRepository wired does not throw") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val bus = ChangeBus()
                    val syncRegistry = SyncRegistry()

                    val bookRepo =
                        BookRepository(
                            db = sql,
                            driver = driver,
                            bus = bus,
                            registry = syncRegistry,
                            contributorRepository = ContributorRepository(sql, bus, syncRegistry),
                            seriesRepository = SeriesRepository(sql, bus, syncRegistry),
                            genreRepository = GenreRepository(sql, bus, syncRegistry),
                            // bookTagRepository = null (default) — cascade is a no-op
                        )

                    // Should not throw even without a bookTagRepository wired.
                    bookRepo.softDelete(BookId("book1"), clientOpId = null)
                }
            }
        }
    })
