package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class BookGenreWriterTest :
    FunSpec({
        test("an unknown genre string auto-creates a live genre and links it, leaving pending empty") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val genreRepository = GenreRepository(sql, ChangeBus(), SyncRegistry())
                val writer = BookGenreWriter(sql, Clock.System, GenreAutoCreator(genreRepository))

                runTest {
                    writer.processGenreStrings(
                        bookId = BookId("book-1"),
                        rawStrings = listOf("Quantum Gardening"),
                        now = System.currentTimeMillis(),
                    )

                    val genreIds =
                        sql.bookGenresQueries
                            .genresForBook("book-1")
                            .executeAsList()
                            .map { it.id }
                    genreIds.size shouldBe 1

                    val genreName =
                        sql.genresQueries
                            .selectById(genreIds.single())
                            .executeAsOneOrNull()
                            ?.name
                    genreName shouldBe "Quantum Gardening"

                    sql.pendingBookGenresQueries
                        .allRows()
                        .executeAsList()
                        .filter { it.book_id == "book-1" }
                        .shouldContainExactly(emptyList())
                }
            }
        }
    })
