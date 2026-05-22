@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.server.db.BookAudioFileTable
import com.calypsan.listenup.server.db.BookChapterTable
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class BookRepositoryReadTest :
    FunSpec({

        test("readPayload returns null for absent book") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                        contributorRepository = ContributorRepository(db, bus, syncRegistry),
                        seriesRepository = SeriesRepository(db, bus, syncRegistry),
                    )
                runTest {
                    suspendTransaction(db = db) {
                        repo.readPayloadForTest("missing").shouldBeNull()
                    }
                }
            }
        }

        test("readPayload assembles the full aggregate: book + contributors + series + chapters + audio files") {
            withInMemoryDatabase {
                val db = this
                val registry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib"))
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        libraryRegistry = registry,
                        contributorRepository = ContributorRepository(db, bus, syncRegistry),
                        seriesRepository = SeriesRepository(db, bus, syncRegistry),
                    )
                runTest {
                    // Resolve the library id first — this bootstraps the `libraries`
                    // row, satisfying the seeded book's library_id FK.
                    val libId = registry.currentLibrary().value
                    transaction(db) {
                        BookTable.insert {
                            it[id] = "b1"
                            it[libraryId] = libId
                            it[title] = "Way of Kings"
                            it[sortTitle] = "Way of Kings"
                            it[totalDuration] = 162_000_000L
                            it[rootRelPath] = "Sanderson/Way of Kings"
                            it[scannedAt] = 1_730_000_000_000L
                            it[revision] = 1L
                            it[createdAt] = 1_730_000_000_000L
                            it[updatedAt] = 1_730_000_000_000L
                            it[coverSource] = "filesystem"
                            it[coverHash] = "deadbeef"
                        }
                        ContributorTable.insert {
                            it[id] = "c1"
                            it[normalizedName] = "brandon sanderson"
                            it[name] = "Brandon Sanderson"
                            it[sortName] = "Sanderson, Brandon"
                        }
                        ContributorTable.insert {
                            it[id] = "c2"
                            it[normalizedName] = "michael kramer"
                            it[name] = "Michael Kramer"
                            it[sortName] = null
                        }
                        BookContributorTable.insert {
                            it[bookId] = "b1"
                            it[contributorId] = "c1"
                            it[role] = "author"
                            it[creditedAs] = null
                            it[ordinal] = 0
                        }
                        BookContributorTable.insert {
                            it[bookId] = "b1"
                            it[contributorId] = "c2"
                            it[role] = "narrator"
                            it[creditedAs] = null
                            it[ordinal] = 1
                        }
                        BookSeriesTable.insert {
                            it[id] = "s1"
                            it[name] = "Stormlight Archive"
                            it[sortName] = null
                        }
                        BookSeriesMembershipTable.insert {
                            it[bookId] = "b1"
                            it[seriesId] = "s1"
                            it[sequence] = "1"
                            it[ordinal] = 0
                        }
                        BookChapterTable.insert {
                            it[bookId] = "b1"
                            it[ordinal] = 0
                            it[id] = "ch1"
                            it[title] = "Prologue"
                            it[duration] = 1_200_000L
                            it[startTime] = 0L
                        }
                        BookChapterTable.insert {
                            it[bookId] = "b1"
                            it[ordinal] = 1
                            it[id] = "ch2"
                            it[title] = "Chapter 1"
                            it[duration] = 1_800_000L
                            it[startTime] = 1_200_000L
                        }
                        BookAudioFileTable.insert {
                            it[bookId] = "b1"
                            it[ordinal] = 0
                            it[id] = "af1"
                            it[filename] = "01.m4b"
                            it[format] = "m4b"
                            it[codec] = "aac"
                            it[duration] = 162_000_000L
                            it[size] = 200_000_000L
                        }
                    }

                    suspendTransaction(db = db) {
                        val payload = repo.readPayloadForTest("b1").shouldNotBeNull()

                        payload.id shouldBe "b1"
                        payload.title shouldBe "Way of Kings"
                        payload.totalDuration shouldBe 162_000_000L
                        payload.rootRelPath shouldBe "Sanderson/Way of Kings"
                        payload.scannedAt shouldBe 1_730_000_000_000L
                        payload.revision shouldBe 1L

                        val cover = payload.cover.shouldNotBeNull()
                        cover.source shouldBe CoverSource.FILESYSTEM
                        cover.hash shouldBe "deadbeef"

                        payload.contributors.size shouldBe 2
                        payload.contributors[0].id shouldBe "c1"
                        payload.contributors[0].role shouldBe "author"
                        payload.contributors[1].id shouldBe "c2"
                        payload.contributors[1].role shouldBe "narrator"

                        payload.series.size shouldBe 1
                        payload.series[0].id shouldBe "s1"
                        payload.series[0].sequence shouldBe "1"

                        payload.chapters.size shouldBe 2
                        payload.chapters.map { it.title } shouldContainExactly listOf("Prologue", "Chapter 1")

                        payload.audioFiles.size shouldBe 1
                        payload.audioFiles[0].filename shouldBe "01.m4b"
                        payload.audioFiles[0].index shouldBe 0
                    }
                }
            }
        }

        test("readPayload returns cover = null when coverHash is absent") {
            withInMemoryDatabase {
                val db = this
                val registry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib"))
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        libraryRegistry = registry,
                        contributorRepository = ContributorRepository(db, bus, syncRegistry),
                        seriesRepository = SeriesRepository(db, bus, syncRegistry),
                    )
                runTest {
                    val libId = registry.currentLibrary().value
                    transaction(db) {
                        BookTable.insert {
                            it[id] = "b2"
                            it[libraryId] = libId
                            it[title] = "No Cover"
                            it[totalDuration] = 0L
                            it[rootRelPath] = "no-cover"
                            it[scannedAt] = 0L
                            it[revision] = 1L
                            it[createdAt] = 0L
                            it[updatedAt] = 0L
                        }
                    }
                    suspendTransaction(db = db) {
                        val payload = repo.readPayloadForTest("b2").shouldNotBeNull()
                        payload.cover.shouldBeNull()
                    }
                }
            }
        }
    })
