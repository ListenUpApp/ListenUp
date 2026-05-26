package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.test.runTest

/**
 * Verifies the cascade added to [BookRepository.softDelete]:
 * when a book is tombstoned, all of its `book_tags` junction rows are also
 * soft-deleted so clients receive per-row tombstones.
 */
class BookRepositorySoftDeleteCascadeTest :
    FunSpec({

        test("softDelete of a book tombstones all its book_tags junction rows") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val bus = ChangeBus()
                    val syncRegistry = SyncRegistry()
                    val tagRepo = TagRepository(db = db, bus = bus, registry = syncRegistry)
                    val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = syncRegistry)

                    val bookRepo =
                        BookRepository(
                            db = db,
                            bus = bus,
                            registry = syncRegistry,
                            _libraryRegistry =
                                LibraryRegistry(
                                    db,
                                    mapOf("LISTENUP_LIBRARY_PATH" to "/lib"),
                                ),
                            contributorRepository = ContributorRepository(db, bus, syncRegistry),
                            seriesRepository = SeriesRepository(db, bus, syncRegistry),
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

        test("softDelete without bookTagRepository wired does not throw") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val bus = ChangeBus()
                    val syncRegistry = SyncRegistry()

                    val bookRepo =
                        BookRepository(
                            db = db,
                            bus = bus,
                            registry = syncRegistry,
                            _libraryRegistry =
                                LibraryRegistry(
                                    db,
                                    mapOf("LISTENUP_LIBRARY_PATH" to "/lib"),
                                ),
                            contributorRepository = ContributorRepository(db, bus, syncRegistry),
                            seriesRepository = SeriesRepository(db, bus, syncRegistry),
                            // bookTagRepository = null (default) — cascade is a no-op
                        )

                    // Should not throw even without a bookTagRepository wired.
                    bookRepo.softDelete(BookId("book1"), clientOpId = null)
                }
            }
        }
    })
