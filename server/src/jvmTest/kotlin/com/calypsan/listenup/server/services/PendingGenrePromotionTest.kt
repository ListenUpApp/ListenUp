package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.PendingBookGenreTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class PendingGenrePromotionTest :
    FunSpec({
        test("promotes a legacy pending string to a live genre and drains the queue") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val writer =
                    BookGenreWriter(db, Clock.System, GenreAutoCreator(GenreRepository(db, ChangeBus(), SyncRegistry())))
                val promotion = PendingGenrePromotion(db, writer)

                runTest {
                    suspendTransaction(db) { PendingBookGenreTable.addPending("book-1", "Cyberpunk", 0L) }

                    promotion.run()

                    suspendTransaction(db) {
                        val genreIds = BookGenreTable.genresForBook("book-1")
                        genreIds.size shouldBe 1

                        val genreName =
                            GenreTable
                                .selectAll()
                                .where { GenreTable.id eq genreIds.single() }
                                .single()[GenreTable.name]
                        genreName shouldBe "Cyberpunk"

                        PendingBookGenreTable
                            .selectAll()
                            .where { PendingBookGenreTable.bookId eq "book-1" }
                            .empty() shouldBe true
                    }
                }
            }
        }

        test("does NOT remove a book's already-live genre while promoting a pending string") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val autoCreator = GenreAutoCreator(GenreRepository(db, ChangeBus(), SyncRegistry()))
                val writer = BookGenreWriter(db, Clock.System, autoCreator)
                val promotion = PendingGenrePromotion(db, writer)

                runTest {
                    val existingGenreId =
                        suspendTransaction(db) {
                            val id = autoCreator.findOrCreateFlatGenreId("Fantasy")
                            BookGenreTable.insertIfAbsent("book-1", id)
                            PendingBookGenreTable.addPending("book-1", "Cyberpunk", 0L)
                            id
                        }

                    promotion.run()

                    suspendTransaction(db) {
                        val names =
                            BookGenreTable
                                .genresForBook("book-1")
                                .map { gid ->
                                    GenreTable.selectAll().where { GenreTable.id eq gid }.single()[GenreTable.name]
                                }
                        names.shouldContainExactlyInAnyOrder("Fantasy", "Cyberpunk")

                        // The pre-existing genre id survived; promotion only added.
                        BookGenreTable.genresForBook("book-1").contains(existingGenreId) shouldBe true
                    }
                }
            }
        }

        test("running twice is idempotent — no duplicate links, no error") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val writer =
                    BookGenreWriter(db, Clock.System, GenreAutoCreator(GenreRepository(db, ChangeBus(), SyncRegistry())))
                val promotion = PendingGenrePromotion(db, writer)

                runTest {
                    suspendTransaction(db) { PendingBookGenreTable.addPending("book-1", "Cyberpunk", 0L) }

                    promotion.run()
                    promotion.run()

                    suspendTransaction(db) {
                        BookGenreTable.genresForBook("book-1").size shouldBe 1
                        PendingBookGenreTable.selectAll().empty() shouldBe true
                    }
                }
            }
        }

        test("an empty pending queue is a graceful no-op") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val writer =
                    BookGenreWriter(db, Clock.System, GenreAutoCreator(GenreRepository(db, ChangeBus(), SyncRegistry())))
                val promotion = PendingGenrePromotion(db, writer)

                runTest {
                    promotion.run()

                    suspendTransaction(db) {
                        BookGenreTable.genresForBook("book-1").isEmpty() shouldBe true
                        PendingBookGenreTable.selectAll().empty() shouldBe true
                    }
                }
            }
        }
    })
