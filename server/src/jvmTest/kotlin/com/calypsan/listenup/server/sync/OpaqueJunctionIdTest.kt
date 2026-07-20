@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import kotlin.uuid.Uuid

/**
 * Regression proof for SERVER-SYNC-04: a server-minted junction row's `id` column must be an
 * opaque value that encodes neither half of the natural pair — the fix that stops an ungated
 * tombstone from leaking the association it protects. One test per junction domain
 * (`collection_books`, `book_tags`, `book_moods`, `shelf_books`).
 *
 * Each test mints the id at the call site (`Uuid.random().toString()`) exactly as the real
 * server-originated call sites do (`CollectionServiceImpl`, `TagServiceImpl`, `MoodServiceImpl`,
 * `ShelfBookRepository.addBook`, scan-time writers) — the repository's `upsert` never mints on
 * the caller's behalf (see `idAsString`'s KDoc for why) — and asserts the persisted row's `id`
 * is non-blank, contains neither natural-key value, and contains no `:` (the tell-tale of the
 * old composite scheme).
 */
class OpaqueJunctionIdTest :
    FunSpec({

        test("a server-minted collection_books row gets an id that encodes neither collectionId nor bookId") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver)

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col-1",
                            libraryId = "test-library",
                            ownerId = "owner",
                            name = "Collection",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(
                            id = Uuid.random().toString(),
                            collectionId = "col-1",
                            bookId = "book-1",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )

                    val row = sql.collectionBooksQueries.selectLiveByCollectionAndBook("col-1", "book-1").executeAsOne()
                    row.id shouldNotContain "col-1"
                    row.id shouldNotContain "book-1"
                    row.id shouldNotContain ":"
                    row.id.isBlank() shouldBe false
                }
            }
        }

        test("a server-minted book_tags row gets an id that encodes neither bookId nor tagId") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    tagRepo.upsert(Tag(id = "tag-1", name = "Sci-Fi", slug = "sci-fi", revision = 0L, updatedAt = 0L))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(
                            id = Uuid.random().toString(),
                            bookId = "book-1",
                            tagId = "tag-1",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )

                    val row = sql.bookTagsQueries.selectByBookId("book-1").executeAsOne()
                    row.id shouldNotContain "book-1"
                    row.id shouldNotContain "tag-1"
                    row.id shouldNotContain ":"
                    row.id.isBlank() shouldBe false
                }
            }
        }

        test("a server-minted book_moods row gets an id that encodes neither bookId nor moodId") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    moodRepo.upsert(Mood(id = "mood-1", name = "Feel-Good", slug = "feel-good", revision = 0L, updatedAt = 0L))
                    bookMoodRepo.upsert(
                        BookMoodSyncPayload(
                            id = Uuid.random().toString(),
                            bookId = "book-1",
                            moodId = "mood-1",
                            createdAt = 0L,
                            revision = 0L,
                        ),
                    )

                    val row = sql.bookMoodsQueries.selectByBookId("book-1").executeAsOne()
                    row.id shouldNotContain "book-1"
                    row.id shouldNotContain "mood-1"
                    row.id shouldNotContain ":"
                    row.id.isBlank() shouldBe false
                }
            }
        }

        test("a server-minted shelf_books row gets an id that encodes neither shelfId nor bookId") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("owner")
                sql.seedTestBook("book-1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = sql, bus = bus, registry = registry)
                val shelfBookRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(
                        ShelfSyncPayload(
                            id = "shelf-1",
                            name = "To Read",
                            description = "",
                            isPrivate = false,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                        ),
                        userId = "owner",
                    )
                    shelfBookRepo.upsert(
                        ShelfBookSyncPayload(
                            id = Uuid.random().toString(),
                            shelfId = "shelf-1",
                            bookId = "book-1",
                            sortOrder = 0,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                        ),
                        userId = "owner",
                    )

                    val row = sql.shelfBooksQueries.selectByShelf("shelf-1").executeAsOne()
                    row.id shouldNotContain "shelf-1"
                    row.id shouldNotContain "book-1"
                    row.id shouldNotContain ":"
                    row.id.isBlank() shouldBe false
                }
            }
        }
    })
