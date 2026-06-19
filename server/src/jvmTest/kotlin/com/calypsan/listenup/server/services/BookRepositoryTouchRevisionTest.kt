@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.calypsan.listenup.server.testing.asSqlDatabase

class BookRepositoryTouchRevisionTest :
    FunSpec({

        test("touchRevision bumps the book's revision") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook(bookId = "b1")
                val repo = buildBookRepository(db)
                runTest {
                    // The seed inserts the row with a hardcoded revision that doesn't track the
                    // global counter, so establish the baseline through one touch first — then
                    // assert the next touch advances past it (the global counter is monotonic).
                    repo.touchRevision(BookId("b1"))
                    val oldRevision =
                        transaction(db) {
                            BookTable
                                .selectAll()
                                .where { BookTable.id eq "b1" }
                                .single()[BookTable.revision]
                        }

                    val result = repo.touchRevision(BookId("b1"))

                    result shouldBe AppResult.Success(Unit)
                    val newRevision =
                        transaction(db) {
                            BookTable
                                .selectAll()
                                .where { BookTable.id eq "b1" }
                                .single()[BookTable.revision]
                        }
                    newRevision shouldBeGreaterThan oldRevision
                }
            }
        }

        test("touchRevision on a missing book returns NotFound") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val repo = buildBookRepository(db)
                runTest {
                    val result = repo.touchRevision(BookId("nope"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }
    })

/**
 * Constructs a [BookRepository] wired to [db] with a fresh [ChangeBus] and
 * [SyncRegistry] and the minimal child repositories. Mirrors the inline
 * construction in `BookRepositoryUpsertTest` — extracted here so both
 * `touchRevision` tests share one builder.
 */
private fun buildBookRepository(db: Database): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = db,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry),
        seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry),
        genreRepository = GenreRepository(db, bus, syncRegistry),
    )
}
