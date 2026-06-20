package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files

class ImportAnalyzerTest :
    FunSpec({

        test("analyze matches progress-bearing items, persists matches.json, and emits events") {
            withInMemoryDatabase {
                val db = this
                val (paths, importId) = stageImport()
                val analyzer = analyzerFor(db, paths)

                runTest {
                    val libId = LibraryRegistry(db.asSqlDatabase()).currentLibrary()
                    transaction(db) {
                        seedAnalyzerBooks(libId.value)
                        seedAnalyzerUser()
                    }
                    val events = mutableListOf<ImportEvent>()
                    val result = analyzer.analyze(importId) { events += it }

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    val analysis = (result as AppResult.Success).data

                    // Two items have progress (book-1 finished, book-2 in-progress); podcast excluded.
                    // book-1 matches by ASIN, book-2 by title+author.
                    analysis.bookMatchCounts[MatchTier.ASIN] shouldBe 1
                    analysis.bookMatchCounts[MatchTier.TITLE_AUTHOR] shouldBe 1
                    analysis.ambiguous.size shouldBe 0
                    analysis.unmatched.size shouldBe 0

                    // Three book sessions resolve to matched books (kings + mist×2); the unresolved-book
                    // and podcast sessions are excluded from the importable estimate.
                    analysis.importableSessionCount shouldBe 3

                    // The ABS user "simon" (email simon@x.test) suggests the seeded ListenUp user.
                    val simon = analysis.userMatches.first { it.absUsername == "simon" }
                    simon.confidence shouldBe MatchTier.STRONG
                    simon.suggestedUserId.shouldNotBeNull()

                    // matches.json carries the resolved item→book map for apply.
                    val resolved = ImportStore(paths).readMatches(importId)
                    resolved.shouldNotBeNull()
                    resolved.itemMatches.shouldContainKey(AbsItemId("book-1"))
                    resolved.itemMatches[AbsItemId("book-1")] shouldBe BookId("lu-kings")
                    resolved.itemMatches[AbsItemId("book-2")] shouldBe BookId("lu-mist")

                    // Event stream: Parsing, Matching(s), then Analyzed.
                    events.first() shouldBe ImportEvent.Parsing
                    events.last().shouldBeInstanceOf<ImportEvent.Analyzed>()
                    events
                        .filterIsInstance<ImportEvent.Matching>()
                        .map { it.total }
                        .shouldContainExactlyInAnyOrder(listOf(2, 2))
                }
            }
        }

        test("Matching events carry currentItem title and running booksMatched tally") {
            withInMemoryDatabase {
                val db = this
                val (paths, importId) = stageImport()
                val analyzer = analyzerFor(db, paths)

                runTest {
                    val libId = LibraryRegistry(db.asSqlDatabase()).currentLibrary()
                    transaction(db) {
                        seedAnalyzerBooks(libId.value)
                        seedAnalyzerUser()
                    }
                    val events = mutableListOf<ImportEvent>()
                    analyzer.analyze(importId) { events += it }

                    val matchingEvents = events.filterIsInstance<ImportEvent.Matching>()
                    // Two progress-bearing items → two Matching events.
                    matchingEvents.size shouldBe 2

                    // Every Matching event must carry a non-null currentItem.
                    matchingEvents.forEach { it.currentItem.shouldNotBeNull() }

                    // The item titles from the fixture are "The Way of Kings" and "Mistborn".
                    matchingEvents
                        .map { it.currentItem }
                        .shouldContainAll(listOf("The Way of Kings", "Mistborn"))

                    // booksMatched grows: after the second event it reflects both resolved books.
                    val finalMatching = matchingEvents.last()
                    finalMatching.booksMatched shouldBe 2

                    // usersMatched: 1 user (simon) with STRONG match.
                    finalMatching.usersMatched shouldBe 1
                }
            }
        }

        test("analyze of a missing import directory returns ImportNotFound") {
            withInMemoryDatabase {
                val db = this
                val home = Files.createTempDirectory("abs-missing-")
                val paths = ImportPaths(home).apply { ensureDirs() }
                val analyzer = analyzerFor(db, paths)

                runTest {
                    val result = analyzer.analyze(ImportId("does-not-exist")) {}
                    result shouldBe AppResult.Failure(ImportError.ImportNotFound())
                }
            }
        }

        test("analyze of a corrupt ABS database returns AnalysisFailed") {
            withInMemoryDatabase {
                val db = this
                val home = Files.createTempDirectory("abs-corrupt-")
                val paths = ImportPaths(home).apply { ensureDirs() }
                val importId = ImportId("abs-corrupt")
                Files.createDirectories(paths.dirFor(importId.value))
                Files.write(paths.absDbFor(importId.value), "not a database".toByteArray())
                val analyzer = analyzerFor(db, paths)

                runTest {
                    val events = mutableListOf<ImportEvent>()
                    val result = analyzer.analyze(importId) { events += it }
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ImportError.AnalysisFailed>()
                    events.last().shouldBeInstanceOf<ImportEvent.Failed>()
                }
            }
        }
    })

/** Stages a synthetic ABS backup db under a fresh import home, returning (paths, importId). */
private fun stageImport(): Pair<ImportPaths, ImportId> {
    val home = Files.createTempDirectory("abs-analyze-")
    val paths = ImportPaths(home).apply { ensureDirs() }
    val importId = ImportId("abs-test")
    Files.createDirectories(paths.dirFor(importId.value))
    buildSyntheticAbsDb(paths.absDbFor(importId.value))
    return paths to importId
}

private fun analyzerFor(
    db: Database,
    paths: ImportPaths,
): ImportAnalyzer =
    ImportAnalyzer(
        reader = AbsBackupReader(),
        store = ImportStore(paths),
        paths = paths,
        bookMatcher = BookMatcher(db.asSqlDatabase()),
        userMatcher = UserMatcher(),
        libraryRegistry = LibraryRegistry(db.asSqlDatabase()),
        sql = db.asSqlDatabase(),
    )

/** Seeds ListenUp books that match the synthetic ABS fixture by ASIN (kings) and title+author (mist). */
private fun seedAnalyzerBooks(libraryId: String) {
    val now = 1_730_000_000_000L
    BookTable.insert {
        it[id] = "lu-kings"
        it[BookTable.libraryId] = libraryId
        it[title] = "The Way of Kings"
        it[asin] = "B00ASIN001"
        it[rootRelPath] = "Sanderson/Way of Kings"
        it[totalDuration] = 1L
        it[scannedAt] = now
        it[revision] = 1L
        it[createdAt] = now
        it[updatedAt] = now
    }
    BookTable.insert {
        it[id] = "lu-mist"
        it[BookTable.libraryId] = libraryId
        it[title] = "Mistborn"
        it[rootRelPath] = "Sanderson/Mistborn-listenup"
        it[totalDuration] = 1L
        it[scannedAt] = now
        it[revision] = 1L
        it[createdAt] = now
        it[updatedAt] = now
    }
    ContributorTable.insert {
        it[id] = "lu-c-sanderson"
        it[normalizedName] = "brandon sanderson"
        it[name] = "Brandon Sanderson"
    }
    // ABS book-2 ("Mistborn") carries no author in the fixture, so title alone resolves lu-mist.
    // We still credit lu-kings' author for completeness of the ASIN-matched book.
    BookContributorTable.insert {
        it[bookId] = "lu-kings"
        it[contributorId] = "lu-c-sanderson"
        it[role] = "author"
        it[ordinal] = 0
    }
}

/** Seeds a ListenUp user whose email matches the ABS fixture user "simon" (simon@x.test). */
private fun seedAnalyzerUser() {
    UserEntity.new("lu-simon") {
        email = "simon@x.test"
        emailNormalized = "simon@x.test"
        passwordHash = "phc"
        role = UserRoleColumn.MEMBER
        displayName = "Simon"
        status = UserStatusColumn.ACTIVE
        createdAt = 1L
        updatedAt = 1L
    }
}
