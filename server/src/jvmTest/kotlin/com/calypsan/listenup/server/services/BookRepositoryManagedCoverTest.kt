@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.calypsan.listenup.server.testing.asSqlDatabase

class BookRepositoryManagedCoverTest :
    FunSpec({

        test("setManagedCover persists cover columns and bumps revision") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry),
                        seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry),
                        genreRepository = GenreRepository(db, bus, syncRegistry),
                    )
                runTest {
                    // Seed a book via upsert so the revision counter starts.
                    repo.upsert(bookPayloadFixture(id = "b1", title = "The Way of Kings"))
                    val revisionBefore =
                        transaction(db) {
                            BookTable
                                .selectAll()
                                .where { BookTable.id eq "b1" }
                                .first()[BookTable.revision]
                        }

                    val result =
                        repo.setManagedCover(
                            id = BookId("b1"),
                            relPath = "covers/b1.jpg",
                            hash = "deadbeef",
                            source = CoverSource.UPLOADED,
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    transaction(db) {
                        val row =
                            BookTable
                                .selectAll()
                                .where { BookTable.id eq "b1" }
                                .first()
                        row[BookTable.coverSource] shouldBe "uploaded"
                        row[BookTable.coverPath] shouldBe "covers/b1.jpg"
                        row[BookTable.coverHash] shouldBe "deadbeef"
                        row[BookTable.revision] shouldBe revisionBefore + 1
                    }
                }
            }
        }

        test("clearManagedCover nulls cover columns and bumps revision") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry),
                        seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry),
                        genreRepository = GenreRepository(db, bus, syncRegistry),
                    )
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "The Way of Kings"))
                    repo.setManagedCover(
                        id = BookId("b1"),
                        relPath = "covers/b1.jpg",
                        hash = "deadbeef",
                        source = CoverSource.UPLOADED,
                    )
                    val revisionBeforeClear =
                        transaction(db) {
                            BookTable
                                .selectAll()
                                .where { BookTable.id eq "b1" }
                                .first()[BookTable.revision]
                        }

                    val result = repo.clearManagedCover(BookId("b1"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    transaction(db) {
                        val row =
                            BookTable
                                .selectAll()
                                .where { BookTable.id eq "b1" }
                                .first()
                        row[BookTable.coverSource].shouldBeNull()
                        row[BookTable.coverPath].shouldBeNull()
                        row[BookTable.coverHash].shouldBeNull()
                        row[BookTable.revision] shouldBe revisionBeforeClear + 1
                    }
                }
            }
        }

        test("setManagedCover returns NotFound for absent book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry),
                        seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry),
                        genreRepository = GenreRepository(db, bus, syncRegistry),
                    )
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry),
                        seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry),
                        genreRepository = GenreRepository(db, bus, syncRegistry),
                    )
                runTest {
                    val result = repo.clearManagedCover(BookId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }
    })
