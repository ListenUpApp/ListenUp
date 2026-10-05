@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private fun repoFor(db: SqlTestDatabases): BookRepository {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributors = ContributorRepository(db.sql, bus, registry)
    val series = SeriesRepository(db.sql, bus, registry)
    return BookRepository(
        db = db.sql,
        driver = db.driver,
        bus = bus,
        registry = registry,
        contributorRepository = contributors,
        seriesRepository = series,
        genreRepository = GenreRepository(db.sql, bus, registry),
    )
}

private val hardcover = ExternalRef("hardcover", "428")

/** A book's catalogue refs and its full release date, through the real write and read path. */
class BookRepositoryExternalRefsTest :
    FunSpec({
        test("the ASIN is the audible ref, and other catalogues' refs ride beside it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = repoFor(this)
                runTest {
                    repo.upsert(bookPayloadFixture("b1", "Project Hail Mary").copy(asin = "B08G9PRS1K", externalRefs = listOf(hardcover)))

                    repo.findById(BookId("b1"))!!.externalRefs shouldBe listOf(ExternalRef("audible", "B08G9PRS1K"), hardcover)
                }
            }
        }

        test("an older client's ASIN edit moves the audible ref and keeps Hardcover's") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = repoFor(this)
                runTest {
                    repo.upsert(bookPayloadFixture("b1", "Project Hail Mary").copy(asin = "B0OLD", externalRefs = listOf(hardcover)))
                    // An older client sends only the ASIN; the server builds the write from the stored book.
                    repo.upsert(repo.findById(BookId("b1"))!!.copy(asin = "B0NEW"))

                    repo.findById(BookId("b1"))!!.externalRefs shouldBe listOf(ExternalRef("audible", "B0NEW"), hardcover)
                }
            }
        }

        test("clearing the ASIN clears the audible ref") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = repoFor(this)
                runTest {
                    repo.upsert(bookPayloadFixture("b1", "Project Hail Mary").copy(asin = "B0OLD"))
                    repo.upsert(repo.findById(BookId("b1"))!!.copy(asin = null))

                    repo.findById(BookId("b1"))!!.externalRefs shouldBe emptyList()
                }
            }
        }

        test("a full release date in the book's year is stored; editing the year alone clears it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = repoFor(this)
                runTest {
                    repo.upsert(bookPayloadFixture("b1", "Project Hail Mary").copy(publishYear = 2021, releaseDate = "2021-05-04"))
                    repo.findById(BookId("b1"))!!.releaseDate shouldBe "2021-05-04"

                    repo.upsert(repo.findById(BookId("b1"))!!.copy(publishYear = 2020))

                    val saved = repo.findById(BookId("b1"))!!
                    saved.publishYear shouldBe 2020
                    saved.releaseDate shouldBe null
                }
            }
        }
    })
