@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.BookAudioFileTable
import com.calypsan.listenup.server.db.BookChapterTable
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.calypsan.listenup.server.testing.asSqlDatabase

class BookRepositoryReadPayloadsTest :
    FunSpec({

        fun newRepo(db: Database): BookRepository {
            val bus = ChangeBus()
            val syncRegistry = SyncRegistry()
            return BookRepository(
                db = db.asSqlDatabase(),
                exposedDb = db,
                bus = bus,
                registry = syncRegistry,
                contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry),
                seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry),
                genreRepository = GenreRepository(db, bus, syncRegistry),
            )
        }

        test("readPayloads equals per-id readPayload, child ordering included") {
            withInMemoryDatabase {
                val db = this
                val repo = newRepo(db)
                runTest {
                    val libId = LibraryRegistry(db).currentLibrary().value
                    transaction(db) {
                        listOf("b1", "b2").forEachIndexed { bi, bookId ->
                            BookTable.insert {
                                it[id] = bookId
                                it[libraryId] = libId
                                it[title] = "Book $bookId"
                                it[sortTitle] = "Book $bookId"
                                it[totalDuration] = 1000L
                                it[rootRelPath] = "path/$bookId"
                                it[scannedAt] = 1L
                                it[revision] = (bi + 1).toLong()
                                it[createdAt] = 1L
                                it[updatedAt] = 1L
                                it[coverSource] = "filesystem"
                                it[coverHash] = "hash-$bookId"
                            }
                            ContributorTable.insert {
                                it[id] = "c-$bookId-0"
                                it[normalizedName] = "a $bookId"
                                it[name] = "Author $bookId"
                                it[sortName] = "Author, $bookId"
                            }
                            ContributorTable.insert {
                                it[id] = "c-$bookId-1"
                                it[normalizedName] = "n $bookId"
                                it[name] = "Narrator $bookId"
                                it[sortName] = null
                            }
                            BookContributorTable.insert {
                                it[BookContributorTable.bookId] = bookId
                                it[contributorId] = "c-$bookId-0"
                                it[role] = "author"
                                it[creditedAs] = null
                                it[ordinal] = 0
                            }
                            BookContributorTable.insert {
                                it[BookContributorTable.bookId] = bookId
                                it[contributorId] = "c-$bookId-1"
                                it[role] = "narrator"
                                it[creditedAs] = null
                                it[ordinal] = 1
                            }
                            BookSeriesTable.insert {
                                it[id] = "s-$bookId"
                                it[normalizedName] = "series $bookId"
                                it[name] = "Series $bookId"
                                it[sortName] = null
                            }
                            BookSeriesMembershipTable.insert {
                                it[BookSeriesMembershipTable.bookId] = bookId
                                it[seriesId] = "s-$bookId"
                                it[sequence] = "1"
                                it[ordinal] = 0
                            }
                            (0..2).forEach { ci ->
                                BookChapterTable.insert {
                                    it[BookChapterTable.bookId] = bookId
                                    it[ordinal] = ci
                                    it[id] = "ch-$bookId-$ci"
                                    it[title] = "Chapter $ci"
                                    it[duration] = 100L
                                    it[startTime] = (ci * 100).toLong()
                                }
                                BookAudioFileTable.insert {
                                    it[BookAudioFileTable.bookId] = bookId
                                    it[ordinal] = ci
                                    it[id] = "af-$bookId-$ci"
                                    it[filename] = "$ci.m4b"
                                    it[format] = "m4b"
                                    it[codec] = "aac"
                                    it[duration] = 100L
                                    it[size] = 100L
                                }
                            }
                            // Two live genres (distinct paths) + one soft-deleted genre — exercises the
                            // genre grouping query's orderBy(path) and its deletedAt.isNull() filter.
                            GenreTable.insert {
                                it[id] = "g-$bookId-fic"
                                it[name] = "Fiction"
                                it[slug] = "fiction-$bookId"
                                it[path] = "fiction"
                                it[parentId] = null
                                it[revision] = 0L
                            }
                            GenreTable.insert {
                                it[id] = "g-$bookId-sf"
                                it[name] = "Science Fiction"
                                it[slug] = "scifi-$bookId"
                                it[path] = "fiction/science-fiction"
                                it[parentId] = null
                                it[revision] = 0L
                            }
                            GenreTable.insert {
                                it[id] = "g-$bookId-dead"
                                it[name] = "Deleted Genre"
                                it[slug] = "dead-$bookId"
                                it[path] = "deleted"
                                it[parentId] = null
                                it[revision] = 0L
                                it[deletedAt] = 123L
                            }
                            BookGenreTable.insert {
                                it[BookGenreTable.bookId] = bookId
                                it[genreId] = "g-$bookId-fic"
                            }
                            BookGenreTable.insert {
                                it[BookGenreTable.bookId] = bookId
                                it[genreId] = "g-$bookId-sf"
                            }
                            BookGenreTable.insert {
                                it[BookGenreTable.bookId] = bookId
                                it[genreId] = "g-$bookId-dead"
                            }
                        }
                    }
                    suspendTransaction(db = db) {
                        val ids = listOf("b1", "b2")
                        val batched = repo.readPayloadsForTest(ids)
                        val perId = ids.mapNotNull { repo.readPayloadForTest(it) }
                        batched shouldBe perId
                    }
                }
            }
        }

        test("readPayloads returns payloads in input-id order") {
            withInMemoryDatabase {
                val db = this
                val repo = newRepo(db)
                runTest {
                    val libId = LibraryRegistry(db).currentLibrary().value
                    transaction(db) {
                        listOf("a", "b", "c").forEach { bookId ->
                            BookTable.insert {
                                it[id] = bookId
                                it[libraryId] = libId
                                it[title] = bookId
                                it[sortTitle] = bookId
                                it[totalDuration] = 0L
                                it[rootRelPath] = bookId
                                it[scannedAt] = 0L
                                it[revision] = 1L
                                it[createdAt] = 0L
                                it[updatedAt] = 0L
                            }
                        }
                    }
                    suspendTransaction(db = db) {
                        repo.readPayloadsForTest(listOf("c", "a", "b")).map { it.id } shouldBe listOf("c", "a", "b")
                    }
                }
            }
        }

        test("readPayloads skips absent ids, keeps surrounding ones") {
            withInMemoryDatabase {
                val db = this
                val repo = newRepo(db)
                runTest {
                    val libId = LibraryRegistry(db).currentLibrary().value
                    transaction(db) {
                        listOf("x", "z").forEach { bookId ->
                            BookTable.insert {
                                it[id] = bookId
                                it[libraryId] = libId
                                it[title] = bookId
                                it[sortTitle] = bookId
                                it[totalDuration] = 0L
                                it[rootRelPath] = bookId
                                it[scannedAt] = 0L
                                it[revision] = 1L
                                it[createdAt] = 0L
                                it[updatedAt] = 0L
                            }
                        }
                    }
                    suspendTransaction(db = db) {
                        repo.readPayloadsForTest(listOf("x", "missing", "z")).map { it.id } shouldBe listOf("x", "z")
                    }
                }
            }
        }

        test("readPayloads on empty input returns empty list") {
            withInMemoryDatabase {
                val db = this
                val repo = newRepo(db)
                runTest {
                    suspendTransaction(db = db) {
                        repo.readPayloadsForTest(emptyList()).shouldBeEmpty()
                    }
                }
            }
        }

        test("readPayloads returns all ids across the inList chunk boundary") {
            withInMemoryDatabase {
                val db = this
                val repo = newRepo(db)
                runTest {
                    val libId = LibraryRegistry(db).currentLibrary().value
                    val ids = (0 until 1000).map { "book-%04d".format(it) }
                    transaction(db) {
                        ids.forEach { bookId ->
                            BookTable.insert {
                                it[id] = bookId
                                it[libraryId] = libId
                                it[title] = bookId
                                it[sortTitle] = bookId
                                it[totalDuration] = 0L
                                it[rootRelPath] = bookId
                                it[scannedAt] = 0L
                                it[revision] = 1L
                                it[createdAt] = 0L
                                it[updatedAt] = 0L
                            }
                        }
                    }
                    suspendTransaction(db = db) {
                        repo.readPayloadsForTest(ids).map { it.id } shouldBe ids
                    }
                }
            }
        }
    })
