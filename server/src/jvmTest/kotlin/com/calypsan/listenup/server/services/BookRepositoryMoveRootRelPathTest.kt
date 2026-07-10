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

/**
 * [BookRepository.moveRootRelPath] — the organizer's DB-side move step (see
 * `com.calypsan.listenup.server.organize.MoveManifestExecutor`). Rewrites `root_rel_path` alone,
 * bumps revision like [BookRepository.touchRevision], and never touches any content column.
 */
class BookRepositoryMoveRootRelPathTest :
    FunSpec({

        test("moveRootRelPath rewrites the path and bumps revision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook(bookId = "b1")
                val repo = buildBookRepository(sql, driver)
                runTest {
                    // The seed inserts the row with a hardcoded revision that doesn't track the
                    // global counter, so establish the baseline through one move first — then
                    // assert the next move advances past it (the global counter is monotonic).
                    repo.moveRootRelPath(BookId("b1"), "warmup/path")
                    val before =
                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOneOrNull()
                            ?: error("book b1 not found")

                    val result = repo.moveRootRelPath(BookId("b1"), "Author/Title")

                    result shouldBe AppResult.Success(Unit)
                    val after =
                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOneOrNull()
                            ?: error("book b1 not found after moveRootRelPath")
                    after.root_rel_path shouldBe "Author/Title"
                    after.revision shouldBeGreaterThan before.revision
                    // No content column changes.
                    after.title shouldBe before.title
                }
            }
        }

        test("moveRootRelPath on a missing book returns NotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = buildBookRepository(sql, driver)
                runTest {
                    val result = repo.moveRootRelPath(BookId("nope"), "Author/Title")

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }
    })

/** Mirrors [BookRepositoryTouchRevisionTest]'s builder — kept file-local to avoid a cross-test-file dependency. */
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
