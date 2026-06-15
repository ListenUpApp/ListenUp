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
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class BookTagWriterTest :
    FunSpec({
        test("writeScanTags links a deduped set of live tags, case-insensitively") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepository = TagRepository(db, bus, registry)
                val bookTagRepository = BookTagRepository(db, bus, registry)
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

        test("writeScanTags is add-only — a manually-added tag survives a rescan") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val tagRepository = TagRepository(db, bus, registry)
                val bookTagRepository = BookTagRepository(db, bus, registry)
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
