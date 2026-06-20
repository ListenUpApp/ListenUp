package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.testing.asSqlDatabase

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class BookMoodWriterTest :
    FunSpec({
        test("writeMoods links a deduped set of live moods, case-insensitively") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepository = MoodRepository(db.asSqlDatabase(), bus, registry)
                val bookMoodRepository = BookMoodRepository(db.asSqlDatabase(), bus, registry)
                val writer = BookMoodWriter(Clock.System, moodRepository, bookMoodRepository)

                runTest {
                    writer.writeMoods(
                        bookId = BookId("book-1"),
                        rawMoods = listOf("Feel-Good", "feel-good", "Tense"),
                    )

                    val names = liveMoodNamesForBook(moodRepository, bookMoodRepository, "book-1")
                    names shouldContainExactlyInAnyOrder listOf("Feel-Good", "Tense")
                }
            }
        }

        test("setBookMoods reconciles to exactly the selection — adds new, removes absent, keeps kept") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepository = MoodRepository(db.asSqlDatabase(), bus, registry)
                val bookMoodRepository = BookMoodRepository(db.asSqlDatabase(), bus, registry)
                val writer = BookMoodWriter(Clock.System, moodRepository, bookMoodRepository)

                runTest {
                    writer.writeMoods(BookId("book-1"), listOf("Cozy", "Tense"))
                    writer.setBookMoods(BookId("book-1"), listOf("Cozy", "Hopeful"))

                    val names = liveMoodNamesForBook(moodRepository, bookMoodRepository, "book-1")
                    names shouldContainExactlyInAnyOrder listOf("Cozy", "Hopeful")
                }
            }
        }

        test("setBookMoods with an empty selection removes all of the book's moods") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepository = MoodRepository(db.asSqlDatabase(), bus, registry)
                val bookMoodRepository = BookMoodRepository(db.asSqlDatabase(), bus, registry)
                val writer = BookMoodWriter(Clock.System, moodRepository, bookMoodRepository)

                runTest {
                    writer.writeMoods(BookId("book-1"), listOf("Cozy", "Tense"))
                    writer.setBookMoods(BookId("book-1"), emptyList())

                    bookMoodRepository.findAllForBook("book-1") shouldBe emptyList()
                }
            }
        }

        test("writeMoods is add-only — a manually-added mood survives a re-apply") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val registry = SyncRegistry()
                val bus = ChangeBus()
                val moodRepository = MoodRepository(db.asSqlDatabase(), bus, registry)
                val bookMoodRepository = BookMoodRepository(db.asSqlDatabase(), bus, registry)
                val writer = BookMoodWriter(Clock.System, moodRepository, bookMoodRepository)

                runTest {
                    // Manually link "Cozy" the same way MoodServiceImpl does:
                    // create the mood in the catalog, then upsert the junction.
                    val cozy =
                        com.calypsan.listenup.api.sync.Mood(
                            id = UUID.randomUUID().toString(),
                            name = "Cozy",
                            slug = "cozy",
                            revision = 0L,
                            updatedAt = 0L,
                        )
                    moodRepository.upsert(cozy) as AppResult.Success
                    bookMoodRepository.upsert(
                        BookMoodSyncPayload(
                            bookId = "book-1",
                            moodId = cozy.id,
                            createdAt = 0L,
                            revision = 0L,
                            deletedAt = null,
                        ),
                    ) as AppResult.Success

                    // Re-apply persists only "Feel-Good".
                    writer.writeMoods(BookId("book-1"), listOf("Feel-Good"))

                    val names = liveMoodNamesForBook(moodRepository, bookMoodRepository, "book-1")
                    names shouldContainExactlyInAnyOrder listOf("Cozy", "Feel-Good")
                }
            }
        }
    })

private suspend fun liveMoodNamesForBook(
    moodRepository: MoodRepository,
    bookMoodRepository: BookMoodRepository,
    bookId: String,
): List<String> =
    bookMoodRepository
        .findAllForBook(bookId)
        .mapNotNull { junction -> moodRepository.findById(junction.moodId)?.name }
