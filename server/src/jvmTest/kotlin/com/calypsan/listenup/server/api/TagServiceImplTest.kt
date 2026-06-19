package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import com.calypsan.listenup.server.testing.rootPrincipal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [TagServiceImpl].
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories; no mocks.
 * The [BookSearchReindexer] is wired with real repos — FTS reindexing is exercised
 * end-to-end where a book_search_map row exists.
 */
class TagServiceImplTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun makeService(
            db: org.jetbrains.exposed.v1.jdbc.Database,
        ): TagServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
            val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
            val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db)
            return TagServiceImpl(tagRepo, bookTagRepo, reindexer, db, fixedClock, principal = rootPrincipal())
        }

        // ── listTags ─────────────────────────────────────────────────────────

        test("listTags returns empty list when no tags exist") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.listTags()
                    result shouldBe AppResult.Success(emptyList())
                }
            }
        }

        test("listTags returns tags with correct bookCount") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    val service = makeService(db)
                    // Add tag to two books.
                    service.addTagToBook(BookId("book1"), "Sci-Fi")
                    service.addTagToBook(BookId("book2"), "Sci-Fi")

                    val result = service.listTags()
                    require(result is AppResult.Success)
                    result.data shouldHaveSize 1
                    result.data.first().name shouldBe "Sci-Fi"
                    result.data.first().bookCount shouldBe 2L
                }
            }
        }

        // ── getTagBySlug ─────────────────────────────────────────────────────

        test("getTagBySlug returns null for nonexistent slug") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.getTagBySlug("nonexistent")
                    result shouldBe AppResult.Success(null)
                }
            }
        }

        test("getTagBySlug returns tag with bookCount when slug matches") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    service.addTagToBook(BookId("book1"), "Sci-Fi")

                    val result = service.getTagBySlug("sci-fi")
                    require(result is AppResult.Success)
                    result.data.shouldNotBeNull()
                    result.data!!.name shouldBe "Sci-Fi"
                    result.data!!.bookCount shouldBe 1L
                }
            }
        }

        // ── addTagToBook ──────────────────────────────────────────────────────

        test("addTagToBook creates new tag and junction") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val result = service.addTagToBook(BookId("book1"), "Mystery")

                    require(result is AppResult.Success)
                    result.data.name shouldBe "Mystery"
                    result.data.slug shouldBe "mystery"
                }
            }
        }

        test("addTagToBook rejects nonexistent book with BookNotFound") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.addTagToBook(BookId("no-such-book"), "Mystery")

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<TagError.BookNotFound>()
                }
            }
        }

        test("addTagToBook rejects empty name with InvalidName") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val result = service.addTagToBook(BookId("book1"), "")

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<TagError.InvalidName>()
                }
            }
        }

        test("addTagToBook is idempotent — same name twice yields one junction") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    service.addTagToBook(BookId("book1"), "Sci-Fi")
                    service.addTagToBook(BookId("book1"), "Sci-Fi")

                    val tags = service.listTagsForBook(BookId("book1"))
                    require(tags is AppResult.Success)
                    tags.data shouldHaveSize 1
                }
            }
        }

        test("addTagToBook reuses existing tag by slug") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    val service = makeService(db)
                    val r1 = service.addTagToBook(BookId("book1"), "Sci-Fi")
                    val r2 = service.addTagToBook(BookId("book2"), "sci-fi")

                    require(r1 is AppResult.Success)
                    require(r2 is AppResult.Success)
                    // Same tag id — existing tag was reused.
                    r1.data.id shouldBe r2.data.id
                }
            }
        }

        // ── removeTagFromBook ─────────────────────────────────────────────────

        test("removeTagFromBook tombstones junction row") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val addResult = service.addTagToBook(BookId("book1"), "Sci-Fi")
                    require(addResult is AppResult.Success)
                    val tagId = TagId(addResult.data.id)

                    val removeResult = service.removeTagFromBook(BookId("book1"), tagId)
                    removeResult shouldBe AppResult.Success(Unit)

                    // Junction should be gone from listTagsForBook.
                    val tags = service.listTagsForBook(BookId("book1"))
                    require(tags is AppResult.Success)
                    tags.data.shouldBeEmpty()
                }
            }
        }

        // ── renameTag ─────────────────────────────────────────────────────────

        test("renameTag updates name but preserves slug") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val addResult = service.addTagToBook(BookId("book1"), "Sci-Fi")
                    require(addResult is AppResult.Success)
                    val tagId = TagId(addResult.data.id)
                    val originalSlug = addResult.data.slug

                    val renameResult = service.renameTag(tagId, "Science Fiction")
                    require(renameResult is AppResult.Success)
                    renameResult.data.name shouldBe "Science Fiction"
                    renameResult.data.slug shouldBe originalSlug
                }
            }
        }

        test("renameTag returns NotFound for missing tag") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.renameTag(TagId("no-such-tag"), "New Name")

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<TagError.NotFound>()
                }
            }
        }

        test("renameTag rejects empty new name with InvalidName") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val addResult = service.addTagToBook(BookId("book1"), "Sci-Fi")
                    require(addResult is AppResult.Success)

                    val result = service.renameTag(TagId(addResult.data.id), "")
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<TagError.InvalidName>()
                }
            }
        }

        // ── deleteTag ─────────────────────────────────────────────────────────

        test("deleteTag tombstones tag and all junctions") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    val service = makeService(db)
                    service.addTagToBook(BookId("book1"), "Sci-Fi")
                    service.addTagToBook(BookId("book2"), "Sci-Fi")

                    val tagId =
                        run {
                            val r = service.getTagBySlug("sci-fi")
                            require(r is AppResult.Success)
                            TagId(r.data!!.id.value)
                        }

                    val deleteResult = service.deleteTag(tagId)
                    deleteResult shouldBe AppResult.Success(Unit)

                    // Tag no longer visible via slug.
                    service.getTagBySlug("sci-fi") shouldBe AppResult.Success(null)

                    // Junctions tombstoned — both books have no tags.
                    val book1Tags = service.listTagsForBook(BookId("book1"))
                    require(book1Tags is AppResult.Success)
                    book1Tags.data.shouldBeEmpty()

                    val book2Tags = service.listTagsForBook(BookId("book2"))
                    require(book2Tags is AppResult.Success)
                    book2Tags.data.shouldBeEmpty()
                }
            }
        }

        test("deleteTag returns NotFound for missing tag") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.deleteTag(TagId("no-such-tag"))

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<TagError.NotFound>()
                }
            }
        }

        // ── listTagsForBook ───────────────────────────────────────────────────

        test("listTagsForBook returns BookNotFound for missing book") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.listTagsForBook(BookId("no-such-book"))

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<TagError.BookNotFound>()
                }
            }
        }

        test("listTagsForBook returns empty list when book has no tags") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val result = service.listTagsForBook(BookId("book1"))

                    result shouldBe AppResult.Success(emptyList())
                }
            }
        }
    })
