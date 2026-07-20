package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class BookTagWriterTest :
    FunSpec({
        test("writeScanTags links a deduped set of live tags, case-insensitively") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepository = TagRepository(sql, bus, registry)
                val bookTagRepository = BookTagRepository(sql, bus, registry)
                val writer = BookTagWriter(Clock.System, tagRepository, bookTagRepository)

                runTest {
                    writer.writeScanTags(
                        bookId = BookId("book-1"),
                        rawTags = listOf("Found Family", "found family", "Time Loop"),
                    )

                    val names = liveTagNamesForBook(tagRepository, bookTagRepository, "book-1")
                    names shouldContainExactlyInAnyOrder listOf("Found Family", "Time Loop")
                }
            }
        }

        test("setBookTags reconciles to exactly the selection — adds new, removes absent, keeps kept") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepository = TagRepository(sql, bus, registry)
                val bookTagRepository = BookTagRepository(sql, bus, registry)
                val writer = BookTagWriter(Clock.System, tagRepository, bookTagRepository)

                runTest {
                    writer.writeScanTags(BookId("book-1"), listOf("Found Family", "Slow Burn"))
                    writer.setBookTags(BookId("book-1"), listOf("Found Family", "Enemies to Lovers"))

                    val names = liveTagNamesForBook(tagRepository, bookTagRepository, "book-1")
                    names shouldContainExactlyInAnyOrder listOf("Found Family", "Enemies to Lovers")
                }
            }
        }

        test("setBookTags with an empty selection removes all of the book's tags") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepository = TagRepository(sql, bus, registry)
                val bookTagRepository = BookTagRepository(sql, bus, registry)
                val writer = BookTagWriter(Clock.System, tagRepository, bookTagRepository)

                runTest {
                    writer.writeScanTags(BookId("book-1"), listOf("Found Family", "Slow Burn"))
                    writer.setBookTags(BookId("book-1"), emptyList())

                    bookTagRepository.findAllForBook("book-1") shouldBe emptyList()
                }
            }
        }

        test("writeScanTags is add-only — a manually-added tag survives a rescan") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepository = TagRepository(sql, bus, registry)
                val bookTagRepository = BookTagRepository(sql, bus, registry)
                val writer = BookTagWriter(Clock.System, tagRepository, bookTagRepository)

                runTest {
                    // Manually link "Favorite" the same way TagServiceImpl does:
                    // create the tag in the catalog, then upsert the junction.
                    val favorite =
                        com.calypsan.listenup.api.sync.Tag(
                            id = UUID.randomUUID().toString(),
                            name = "Favorite",
                            slug = "favorite",
                            revision = 0L,
                            updatedAt = 0L,
                        )
                    tagRepository.upsert(favorite) as AppResult.Success
                    bookTagRepository.upsert(
                        BookTagSyncPayload(
                            id = "book-1:${favorite.id}",
                            bookId = "book-1",
                            tagId = favorite.id,
                            createdAt = 0L,
                            revision = 0L,
                            deletedAt = null,
                        ),
                    ) as AppResult.Success

                    // Rescan persists only "Found Family".
                    writer.writeScanTags(BookId("book-1"), listOf("Found Family"))

                    val names = liveTagNamesForBook(tagRepository, bookTagRepository, "book-1")
                    names shouldContainExactlyInAnyOrder listOf("Favorite", "Found Family")
                }
            }
        }
    })

private suspend fun liveTagNamesForBook(
    tagRepository: TagRepository,
    bookTagRepository: BookTagRepository,
    bookId: String,
): List<String> =
    bookTagRepository
        .findAllForBook(bookId)
        .mapNotNull { junction -> tagRepository.findById(junction.tagId)?.name }
