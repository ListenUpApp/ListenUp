package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlinx.io.files.Path as IoPath

class ImportAnalyzerTest :
    FunSpec({

        test("analyze matches progress-bearing items, persists matches.json, and emits events") {
            withSqlDatabase {
                val dbs = this
                val (paths, importId) = stageImport()
                val analyzer = analyzerFor(dbs, paths)

                runTest {
                    dbs.sql.seedTestLibraryAndFolder()
                    val libId = LibraryRegistry(dbs.sql).currentLibrary()
                    dbs.sql.transaction {
                        seedAnalyzerBooks(dbs.sql, libId.value)
                        seedAnalyzerUser(dbs.sql)
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
            withSqlDatabase {
                val dbs = this
                val (paths, importId) = stageImport()
                val analyzer = analyzerFor(dbs, paths)

                runTest {
                    dbs.sql.seedTestLibraryAndFolder()
                    val libId = LibraryRegistry(dbs.sql).currentLibrary()
                    dbs.sql.transaction {
                        seedAnalyzerBooks(dbs.sql, libId.value)
                        seedAnalyzerUser(dbs.sql)
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
            withSqlDatabase {
                val dbs = this
                val home = Files.createTempDirectory("abs-missing-")
                val paths = ImportPaths(IoPath(home.toString())).apply { ensureDirs() }
                val analyzer = analyzerFor(dbs, paths)

                runTest {
                    val result = analyzer.analyze(ImportId("does-not-exist")) {}
                    result shouldBe AppResult.Failure(ImportError.ImportNotFound())
                }
            }
        }

        test("analyze of a corrupt ABS database returns AnalysisFailed") {
            withSqlDatabase {
                val dbs = this
                val home = Files.createTempDirectory("abs-corrupt-")
                val paths = ImportPaths(IoPath(home.toString())).apply { ensureDirs() }
                val importId = ImportId("abs-corrupt")
                Files.createDirectories(
                    java.nio.file.Path
                        .of(paths.dirFor(importId.value).toString()),
                )
                Files.write(
                    java.nio.file.Path
                        .of(paths.absDbFor(importId.value).toString()),
                    "not a database".toByteArray(),
                )
                val analyzer = analyzerFor(dbs, paths)

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
    val paths = ImportPaths(IoPath(home.toString())).apply { ensureDirs() }
    val importId = ImportId("abs-test")
    Files.createDirectories(
        java.nio.file.Path
            .of(paths.dirFor(importId.value).toString()),
    )
    buildSyntheticAbsDb(
        java.nio.file.Path
            .of(paths.absDbFor(importId.value).toString()),
    )
    return paths to importId
}

private fun analyzerFor(
    dbs: SqlTestDatabases,
    paths: ImportPaths,
): ImportAnalyzer =
    ImportAnalyzer(
        reader = AbsBackupReader(),
        store = ImportStore(paths),
        paths = paths,
        bookMatcher = BookMatcher(dbs.sql),
        userMatcher = UserMatcher(),
        libraryRegistry = LibraryRegistry(dbs.sql),
        sql = dbs.sql,
    )

/** Seeds ListenUp books that match the synthetic ABS fixture by ASIN (kings) and title+author (mist). */
private fun seedAnalyzerBooks(
    sql: ListenUpDatabase,
    libraryId: String,
) {
    val now = 1_730_000_000_000L
    sql.booksQueries.insert(
        id = "lu-kings",
        library_id = libraryId,
        folder_id = "test-folder",
        title = "The Way of Kings",
        sort_title = null,
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = "B00ASIN001",
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = 1L,
        cover_source = null,
        cover_path = null,
        cover_hash = null,
        field_provenance = "{}",
        root_rel_path = "Sanderson/Way of Kings",
        inode = null,
        scanned_at = now,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
    sql.booksQueries.insert(
        id = "lu-mist",
        library_id = libraryId,
        folder_id = "test-folder",
        title = "Mistborn",
        sort_title = null,
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = 1L,
        cover_source = null,
        cover_path = null,
        cover_hash = null,
        field_provenance = "{}",
        root_rel_path = "Sanderson/Mistborn-listenup",
        inode = null,
        scanned_at = now,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
    sql.contributorsQueries.insert(
        id = "lu-c-sanderson",
        normalized_name = "brandon sanderson",
        name = "Brandon Sanderson",
        sort_name = null,
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = null,
        client_op_id = null,
        asin = null,
        description = null,
        image_path = null,
        image_blur_hash = null,
        birth_date = null,
        death_date = null,
        website = null,
    )
    // ABS book-2 ("Mistborn") carries no author in the fixture, so title alone resolves lu-mist.
    // We still credit lu-kings' author for completeness of the ASIN-matched book.
    sql.bookContributorsQueries.insert(
        book_id = "lu-kings",
        contributor_id = "lu-c-sanderson",
        role = "author",
        credited_as = null,
        ordinal = 0L,
    )
}

/** Seeds a ListenUp user whose email matches the ABS fixture user "simon" (simon@x.test). */
private fun seedAnalyzerUser(sql: ListenUpDatabase) {
    sql.usersQueries.insert(
        id = "lu-simon",
        email = "simon@x.test",
        email_normalized = "simon@x.test",
        password_hash = "phc",
        role = "MEMBER",
        display_name = "Simon",
        status = "ACTIVE",
        created_at = 1L,
        updated_at = 1L,
        last_login_at = null,
        can_edit = 1L,
        can_share = 1L,
        approved_by = null,
        approved_at = null,
        deleted_at = null,
        invited_by = null,
        tagline = null,
        avatar_type = "auto",
        timezone = "UTC",
    )
}
