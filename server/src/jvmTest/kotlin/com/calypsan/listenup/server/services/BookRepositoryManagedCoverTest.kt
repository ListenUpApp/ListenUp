@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookRepositoryManagedCoverTest :
    FunSpec({

        test("setManagedCover persists cover columns and bumps revision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    // Seed a book via upsert so the revision counter starts.
                    repo.upsert(bookPayloadFixture(id = "b1", title = "The Way of Kings"))
                    val revisionBefore =
                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOneOrNull()
                            ?.revision
                            ?: error("book b1 not found")

                    val result =
                        repo.setManagedCover(
                            id = BookId("b1"),
                            relPath = "covers/b1.jpg",
                            hash = "deadbeef",
                            source = CoverSource.UPLOADED,
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row =
                        sql.booksQueries.selectById("b1").executeAsOneOrNull()
                            ?: error("book b1 not found after setManagedCover")
                    row.cover_source shouldBe "uploaded"
                    row.cover_path shouldBe "covers/b1.jpg"
                    row.cover_hash shouldBe "deadbeef"
                    row.revision shouldBe revisionBefore + 1
                }
            }
        }

        test("clearManagedCover nulls cover columns and bumps revision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "The Way of Kings"))
                    repo.setManagedCover(
                        id = BookId("b1"),
                        relPath = "covers/b1.jpg",
                        hash = "deadbeef",
                        source = CoverSource.UPLOADED,
                    )
                    val revisionBeforeClear =
                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOneOrNull()
                            ?.revision
                            ?: error("book b1 not found")

                    val result = repo.clearManagedCover(BookId("b1"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row =
                        sql.booksQueries.selectById("b1").executeAsOneOrNull()
                            ?: error("book b1 not found after clearManagedCover")
                    row.cover_source.shouldBeNull()
                    row.cover_path.shouldBeNull()
                    row.cover_hash.shouldBeNull()
                    row.revision shouldBe revisionBeforeClear + 1
                }
            }
        }

        test("setManagedCover returns NotFound for absent book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    val result =
                        repo.setManagedCover(
                            id = BookId("missing"),
                            relPath = "covers/missing.jpg",
                            hash = "aabbcc",
                            source = CoverSource.UPLOADED,
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        test("clearManagedCover returns NotFound for absent book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    val result = repo.clearManagedCover(BookId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }
    })

private fun SqlTestDatabases.makeRepo(): BookRepository {
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
