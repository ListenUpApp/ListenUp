@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookRepositoryTouchRevisionTest :
    FunSpec({

        test("touchRevision bumps the book's revision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook(bookId = "b1")
                val repo = buildBookRepository(sql, driver)
                runTest {
                    // The seed inserts the row with a hardcoded revision that doesn't track the
                    // global counter, so establish the baseline through one touch first — then
                    // assert the next touch advances past it (the global counter is monotonic).
                    repo.touchRevision(BookId("b1"))
                    val oldRevision =
                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOneOrNull()
                            ?.revision
                            ?: error("book b1 not found")

                    val result = repo.touchRevision(BookId("b1"))

                    result shouldBe AppResult.Success(Unit)
                    val newRevision =
                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOneOrNull()
                            ?.revision
                            ?: error("book b1 not found after touchRevision")
                    newRevision shouldBeGreaterThan oldRevision
                }
            }
        }

        test("touchRevision on a missing book returns NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = buildBookRepository(sql, driver)
                runTest {
                    val result = repo.touchRevision(BookId("nope"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }
    })

/**
 * Constructs a [BookRepository] wired to [sql] with a fresh [ChangeBus] and
 * [SyncRegistry] and the minimal child repositories. Mirrors the inline
 * construction in `BookRepositoryUpsertTest` — extracted here so both
 * `touchRevision` tests share one builder.
 */
private fun buildBookRepository(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}
