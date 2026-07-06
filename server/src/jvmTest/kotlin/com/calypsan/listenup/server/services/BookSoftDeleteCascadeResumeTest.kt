package com.calypsan.listenup.server.services

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
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
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Pins the partial-failure and crash-resume semantics of [BookRepository.softDelete] (plan 094).
 *
 * The cascade is deliberately multi-transaction: the book tombstone commits first, then each junction
 * cascade (`book_tags`, `book_moods`, `collection_books`) commits in its own transaction, then the
 * orphan-parent purge runs one transaction per parent. A crash between those steps leaves a
 * half-cascaded book that NO scan sweep ever re-drives (they select live books only). These tests
 * reproduce each crash boundary — via selective wiring (a null junction repo is a "committed nothing"
 * for that step) and via a genuine mid-cascade throw — and prove that a re-invoked `softDelete`
 * completes the unfinished cascade.
 */
class BookSoftDeleteCascadeResumeTest :
    FunSpec({

        // Full leaf-repo graph sharing one database; `bookRepo(...)` builds a BookRepository whose
        // cascade wiring can be narrowed to reproduce a specific crash boundary (a null junction repo
        // is a committed-nothing for that cascade step).
        class CascadeGraph(
            val sql: ListenUpDatabase,
            val driver: SqlDriver,
        ) {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
            val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
            val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
            val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)
            val collectionBookRepo = CollectionBookRepository(sql, bus, registry, driver = driver)
            val contributorRepo = ContributorRepository(sql, bus, registry)
            val seriesRepo = SeriesRepository(sql, bus, registry)
            val genreRepo = GenreRepository(sql, bus, registry)
            val purger =
                OrphanParentPurger(
                    db = sql,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                    tagRepository = tagRepo,
                    moodRepository = moodRepo,
                    bookTagRepository = bookTagRepo,
                    bookMoodRepository = bookMoodRepo,
                )

            // Each BookRepository registers its 'books' domain in the registry on construction, so the
            // partial and resume instances need distinct registries (a shared one throws "already
            // registered"). The cascade calls the leaf repos directly, not via the registry, so an
            // isolated registry per instance is behaviourally transparent.
            fun bookRepo(
                wireTags: Boolean = true,
                wireMoods: Boolean = true,
                wireCollections: Boolean = true,
                wirePurger: Boolean = true,
            ): BookRepository =
                BookRepository(
                    db = sql,
                    driver = driver,
                    bus = bus,
                    registry = SyncRegistry(),
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                    collectionBookRepository = if (wireCollections) collectionBookRepo else null,
                    tagRepository = tagRepo,
                    bookTagRepository = if (wireTags) bookTagRepo else null,
                    bookMoodRepository = if (wireMoods) bookMoodRepo else null,
                    orphanParentPurger = if (wirePurger) purger else null,
                )
        }

        // Attaches a tag, a mood, and a NORMAL-collection membership to [bookId].
        suspend fun CascadeGraph.attachTagMoodCollection(
            bookId: String,
            tagId: String,
            moodId: String,
            collectionId: String,
        ) {
            val now = System.currentTimeMillis()
            sql.collectionsQueries.insert(
                id = collectionId,
                library_id = "test-library",
                owner_id = "owner1",
                name = "Faves-$collectionId",
                type = "NORMAL",
                created_at = now,
                updated_at = now,
                revision = 0L,
                deleted_at = null,
                client_op_id = null,
            )
            tagRepo.upsert(Tag(id = tagId, name = tagId, slug = tagId, revision = 0, updatedAt = 0))
            moodRepo.upsert(Mood(id = moodId, name = moodId, slug = moodId, revision = 0, updatedAt = 0))
            bookTagRepo.upsert(BookTagSyncPayload(bookId = bookId, tagId = tagId, createdAt = 1000L, revision = 0L))
            bookMoodRepo.upsert(BookMoodSyncPayload(bookId = bookId, moodId = moodId, createdAt = 1000L, revision = 0L))
            collectionBookRepo.upsert(
                CollectionBookSyncPayload(collectionId = collectionId, bookId = bookId, createdAt = 1000L, revision = 0L),
            )
        }

        test("boundary 2: crash after book tombstone, before any cascade, leaves junctions and parents live") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val graph = CascadeGraph(sql, driver)
                    graph.attachTagMoodCollection("book1", "t1", "m1", "c1")
                    val soleAuthor = graph.contributorRepo.resolveOrCreate("Sole Author", "Author, Sole").value
                    sql.bookContributorsQueries.insert("book1", soleAuthor, "author", null, 0)

                    // No junction repos and no purger wired → every cascade step is a committed-nothing.
                    graph.bookRepo(wireTags = false, wireMoods = false, wireCollections = false, wirePurger = false)
                        .softDelete(BookId("book1"), clientOpId = null)

                    // Book is tombstoned, but nothing downstream ran.
                    sql.booksQueries.selectById("book1").executeAsOne().deleted_at.shouldNotBeNull()
                    graph.bookTagRepo.findAllForBook("book1").shouldHaveSize(1)
                    graph.bookMoodRepo.findAllForBook("book1").shouldHaveSize(1)
                    sql.collectionBooksQueries.liveCollectionIdsForBook("book1").executeAsList().shouldContainExactly("c1")
                    sql.contributorsQueries.selectById(soleAuthor).executeAsOne().deleted_at.shouldBeNull()
                }
            }
        }

        test("boundary 4: crash after tag+mood cascades, before collection_books, leaves membership live") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val graph = CascadeGraph(sql, driver)
                    graph.attachTagMoodCollection("book1", "t1", "m1", "c1")

                    // Tag+mood repos wired, collections + purger not → cascade stops at collection_books.
                    graph.bookRepo(wireTags = true, wireMoods = true, wireCollections = false, wirePurger = false)
                        .softDelete(BookId("book1"), clientOpId = null)

                    sql.booksQueries.selectById("book1").executeAsOne().deleted_at.shouldNotBeNull()
                    graph.bookTagRepo.findAllForBook("book1").shouldBeEmpty()
                    graph.bookMoodRepo.findAllForBook("book1").shouldBeEmpty()
                    // The uncascaded step: the book still sits in its collection.
                    sql.collectionBooksQueries.liveCollectionIdsForBook("book1").executeAsList().shouldContainExactly("c1")
                }
            }
        }

        test("resume from boundary 2 completes junction cascade, purges sole parents, spares shared ones") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val graph = CascadeGraph(sql, driver)
                    graph.attachTagMoodCollection("book1", "t1", "m1", "c1")
                    // Sole parents (book1 only) and shared parents (book1 + book2, survive the removal).
                    val soleAuthor = graph.contributorRepo.resolveOrCreate("Sole Author", "Author, Sole").value
                    val sharedAuthor = graph.contributorRepo.resolveOrCreate("Shared Author", "Author, Shared").value
                    sql.bookContributorsQueries.insert("book1", soleAuthor, "author", null, 0)
                    sql.bookContributorsQueries.insert("book1", sharedAuthor, "author", null, 1)
                    sql.bookContributorsQueries.insert("book2", sharedAuthor, "author", null, 0)
                    val soleSeries = graph.seriesRepo.resolveOrCreate("Sole Series").value
                    val sharedSeries = graph.seriesRepo.resolveOrCreate("Shared Series").value
                    sql.bookSeriesMembershipsQueries.insert("book1", soleSeries, null, 0)
                    sql.bookSeriesMembershipsQueries.insert("book1", sharedSeries, null, 1)
                    sql.bookSeriesMembershipsQueries.insert("book2", sharedSeries, null, 0)

                    // Partial: book tombstoned, nothing else (boundary 2).
                    graph.bookRepo(wireTags = false, wireMoods = false, wireCollections = false, wirePurger = false)
                        .softDelete(BookId("book1"), clientOpId = null)
                    val revisionAfterPartial = sql.booksQueries.selectById("book1").executeAsOne().revision

                    // Resume on the fully-wired repo.
                    val result = graph.bookRepo().softDelete(BookId("book1"), clientOpId = null)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Junctions now tombstoned.
                    graph.bookTagRepo.findAllForBook("book1").shouldBeEmpty()
                    graph.bookMoodRepo.findAllForBook("book1").shouldBeEmpty()
                    sql.collectionBooksQueries.liveCollectionIdsForBook("book1").executeAsList().shouldBeEmpty()
                    // Sole parents purged; shared parents (book2 still live) survive.
                    sql.contributorsQueries.selectById(soleAuthor).executeAsOne().deleted_at.shouldNotBeNull()
                    sql.contributorsQueries.selectById(sharedAuthor).executeAsOne().deleted_at.shouldBeNull()
                    sql.seriesQueries.selectById(soleSeries).executeAsOne().deleted_at.shouldNotBeNull()
                    sql.seriesQueries.selectById(sharedSeries).executeAsOne().deleted_at.shouldBeNull()
                    // The re-stamp bumped the revision.
                    sql.booksQueries.selectById("book1").executeAsOne().revision.shouldBeGreaterThan(revisionAfterPartial)
                }
            }
        }

        test("resume from boundary 4 tombstones the collection membership and purges the sole contributor") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val graph = CascadeGraph(sql, driver)
                    graph.attachTagMoodCollection("book1", "t1", "m1", "c1")
                    val soleAuthor = graph.contributorRepo.resolveOrCreate("Sole Author", "Author, Sole").value
                    sql.bookContributorsQueries.insert("book1", soleAuthor, "author", null, 0)

                    // Partial: tags+moods cascaded, collections + purge did not (boundary 4).
                    graph.bookRepo(wireTags = true, wireMoods = true, wireCollections = false, wirePurger = false)
                        .softDelete(BookId("book1"), clientOpId = null)
                    sql.collectionBooksQueries.liveCollectionIdsForBook("book1").executeAsList().shouldContainExactly("c1")

                    // Resume completes the collection cascade and the hard-replace-parent purge.
                    val result = graph.bookRepo().softDelete(BookId("book1"), clientOpId = null)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    sql.collectionBooksQueries.liveCollectionIdsForBook("book1").executeAsList().shouldBeEmpty()
                    sql.contributorsQueries.selectById(soleAuthor).executeAsOne().deleted_at.shouldNotBeNull()
                }
            }
        }

        test("a mid-cascade throw propagates, keeps the committed prefix, and resume completes after repair") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val graph = CascadeGraph(sql, driver)
                    graph.attachTagMoodCollection("book1", "t1", "m1", "c1")
                    val soleAuthor = graph.contributorRepo.resolveOrCreate("Sole Author", "Author, Sole").value
                    sql.bookContributorsQueries.insert("book1", soleAuthor, "author", null, 0)
                    val bookRepo = graph.bookRepo()

                    // Rip collection_books out from under the cascade: tag+mood cascades run first, so the
                    // throw lands exactly at the collectionBookRepository step (boundary 4→5).
                    driver.execute(null, "ALTER TABLE collection_books RENAME TO collection_books_bak", 0)

                    shouldThrowAny { bookRepo.softDelete(BookId("book1"), clientOpId = null) }

                    // Committed prefix: book + tags + moods tombstoned; purge did NOT run (contributor live).
                    sql.booksQueries.selectById("book1").executeAsOne().deleted_at.shouldNotBeNull()
                    graph.bookTagRepo.findAllForBook("book1").shouldBeEmpty()
                    graph.bookMoodRepo.findAllForBook("book1").shouldBeEmpty()
                    sql.contributorsQueries.selectById(soleAuthor).executeAsOne().deleted_at.shouldBeNull()

                    // Repair and resume → completes the collection cascade and the hard-replace-parent purge.
                    driver.execute(null, "ALTER TABLE collection_books_bak RENAME TO collection_books", 0)
                    val result = bookRepo.softDelete(BookId("book1"), clientOpId = null)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    sql.collectionBooksQueries.liveCollectionIdsForBook("book1").executeAsList().shouldBeEmpty()
                    sql.contributorsQueries.selectById(soleAuthor).executeAsOne().deleted_at.shouldNotBeNull()
                }
            }
        }
    })
