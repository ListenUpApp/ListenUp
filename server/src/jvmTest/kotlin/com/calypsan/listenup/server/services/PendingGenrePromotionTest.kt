package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class PendingGenrePromotionTest :
    FunSpec({
        test("promotes a legacy pending string to a live genre and drains the queue") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val writer =
                    BookGenreWriter(
                        sql,
                        Clock.System,
                        GenreAutoCreator(GenreRepository(sql, ChangeBus(), SyncRegistry())),
                    )
                val promotion = PendingGenrePromotion(sql, writer)

                runTest {
                    sql.transaction {
                        sql.pendingBookGenresQueries.addPending(
                            book_id = "book-1",
                            raw_string = "Cyberpunk",
                            first_seen_at = 0L,
                        )
                    }

                    promotion.run()

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
                    genreName shouldBe "Cyberpunk"

                    sql.pendingBookGenresQueries
                        .allRows()
                        .executeAsList()
                        .filter { it.book_id == "book-1" }
                        .isEmpty() shouldBe true
                }
            }
        }

        test("does NOT remove a book's already-live genre while promoting a pending string") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val autoCreator = GenreAutoCreator(GenreRepository(sql, ChangeBus(), SyncRegistry()))
                val writer = BookGenreWriter(sql, Clock.System, autoCreator)
                val promotion = PendingGenrePromotion(sql, writer)

                runTest {
                    val existingGenreId = autoCreator.findOrCreateFlatGenreId("Fantasy")
                    sql.transaction {
                        sql.bookGenresQueries.insertIfAbsent(book_id = "book-1", genre_id = existingGenreId)
                        sql.pendingBookGenresQueries.addPending(
                            book_id = "book-1",
                            raw_string = "Cyberpunk",
                            first_seen_at = 0L,
                        )
                    }

                    promotion.run()

                    val names =
                        sql.bookGenresQueries
                            .genresForBook("book-1")
                            .executeAsList()
                            .map { it.name }
                    names.shouldContainExactlyInAnyOrder("Fantasy", "Cyberpunk")

                    // The pre-existing genre id survived; promotion only added.
                    val bookGenreIds =
                        sql.bookGenresQueries
                            .genresForBook("book-1")
                            .executeAsList()
                            .map { it.id }
                    bookGenreIds.contains(existingGenreId) shouldBe true
                }
            }
        }

        test("running twice is idempotent — no duplicate links, no error") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val writer =
                    BookGenreWriter(
                        sql,
                        Clock.System,
                        GenreAutoCreator(GenreRepository(sql, ChangeBus(), SyncRegistry())),
                    )
                val promotion = PendingGenrePromotion(sql, writer)

                runTest {
                    sql.transaction {
                        sql.pendingBookGenresQueries.addPending(
                            book_id = "book-1",
                            raw_string = "Cyberpunk",
                            first_seen_at = 0L,
                        )
                    }

                    promotion.run()
                    promotion.run()

                    sql.bookGenresQueries
                        .genresForBook("book-1")
                        .executeAsList()
                        .size shouldBe 1
                    sql.pendingBookGenresQueries
                        .allRows()
                        .executeAsList()
                        .isEmpty() shouldBe true
                }
            }
        }

        test("an empty pending queue is a graceful no-op") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")

                val writer =
                    BookGenreWriter(
                        sql,
                        Clock.System,
                        GenreAutoCreator(GenreRepository(sql, ChangeBus(), SyncRegistry())),
                    )
                val promotion = PendingGenrePromotion(sql, writer)

                runTest {
                    promotion.run()

                    sql.bookGenresQueries
                        .genresForBook("book-1")
                        .executeAsList()
                        .isEmpty() shouldBe true
                    sql.pendingBookGenresQueries
                        .allRows()
                        .executeAsList()
                        .isEmpty() shouldBe true
                }
            }
        }
    })
