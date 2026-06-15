package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.PendingBookGenreTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class BookGenreWriterTest :
    FunSpec({
        test("an unknown genre string auto-creates a live genre and links it, leaving pending empty") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-1")

                val genreRepository = GenreRepository(db, ChangeBus(), SyncRegistry())
                val writer = BookGenreWriter(db, Clock.System, GenreAutoCreator(genreRepository))

                runTest {
                    suspendTransaction(db) {
                        writer.processGenreStrings(
                            bookId = BookId("book-1"),
                            rawStrings = listOf("Quantum Gardening"),
                            now = System.currentTimeMillis(),
                        )
                    }

                    suspendTransaction(db) {
                        val genreIds = BookGenreTable.genresForBook("book-1")
                        genreIds.size shouldBe 1

                        val genreName =
                            GenreTable
                                .selectAll()
                                .where { GenreTable.id eq genreIds.single() }
                                .single()[GenreTable.name]
                        genreName shouldBe "Quantum Gardening"

                        PendingBookGenreTable
                            .selectAll()
                            .where { PendingBookGenreTable.bookId eq "book-1" }
                            .toList()
                            .shouldContainExactly(emptyList())
                    }
                }
            }
        }
    })
