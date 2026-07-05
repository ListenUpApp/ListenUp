package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookTagSyncPayload
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest

/**
 * Verifies the deletion-based orphan purge in [BookRepository.softDelete] (the user "nuke" directive):
 * removing a book tombstones any contributor / tag left with zero live book children, while a parent
 * still linked to another live book survives.
 */
class BookRemovalOrphanPurgeTest :
    FunSpec({

        fun buildRepo(
            sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
            driver: app.cash.sqldelight.db.SqlDriver,
        ): Triple<BookRepository, TagRepository, BookTagRepository> {
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
            val bookRepo =
                BookRepository(
                    db = sql,
                    driver = driver,
                    bus = bus,
                    registry = registry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                    collectionBookRepository = collectionBookRepo,
                    tagRepository = tagRepo,
                    bookTagRepository = bookTagRepo,
                    bookMoodRepository = bookMoodRepo,
                    orphanParentPurger = purger,
                )
            return Triple(bookRepo, tagRepo, bookTagRepo)
        }

        test("removing a book with a sole contributor purges the orphan contributor but keeps a shared one") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val (bookRepo, _, _) = buildRepo(sql, driver)
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val contributorRepo = ContributorRepository(sql, bus, registry)
                    // c1 → book1 only (will orphan); c2 → book1 + book2 (survives).
                    val c1 = contributorRepo.resolveOrCreate("Sole Author", "Author, Sole").value
                    val c2 = contributorRepo.resolveOrCreate("Shared Author", "Author, Shared").value
                    sql.bookContributorsQueries.insert("book1", c1, "author", null, 0)
                    sql.bookContributorsQueries.insert("book1", c2, "author", null, 1)
                    sql.bookContributorsQueries.insert("book2", c2, "author", null, 0)

                    bookRepo.softDelete(BookId("book1"), clientOpId = null)

                    // c1 is orphaned (its only book is gone) → tombstoned; c2 survives (book2 lives).
                    sql.contributorsQueries
                        .selectById(c1)
                        .executeAsOne()
                        .deleted_at
                        .shouldNotBeNull()
                    sql.contributorsQueries
                        .selectById(c2)
                        .executeAsOne()
                        .deleted_at
                        .shouldBeNull()
                }
            }
        }

        test("removing a book with a sole tag purges the orphan tag") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val (bookRepo, tagRepo, bookTagRepo) = buildRepo(sql, driver)
                    tagRepo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(bookId = "book1", tagId = "t1", createdAt = 1000L, revision = 0L),
                    )

                    bookRepo.softDelete(BookId("book1"), clientOpId = null)

                    sql.tagsQueries
                        .selectById("t1")
                        .executeAsOne()
                        .deleted_at
                        .shouldNotBeNull()
                }
            }
        }
    })
