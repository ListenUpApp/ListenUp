@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Series merge receipts and undo. Real migrated SQLite, repositories on the same database.
 *
 * Undo's contract: put back exactly what is still as the merge left it (the book is live and
 * still in the target), leave every later decision alone, and report how many books it skipped.
 */
class SeriesMergeUndoTest :
    FunSpec({

        // ── Recording ──────────────────────────────────────────────────────────

        test("a merge records every source membership, including a book the target already held") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.putBook("b1", BookSeriesPayload(s.value, "Source", 1.0))
                    f.putBook("b2", BookSeriesPayload(s.value, "Source", 2.5), BookSeriesPayload(t.value, "Target", 7.0))

                    f.service.mergeSeries(s, t).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val receipt =
                        sql.seriesMergeReceiptsQueries
                            .selectOpenForTarget(t.value)
                            .executeAsList()
                            .single()
                    receipt.book_count shouldBe 2L
                    val rows = sql.seriesMergeReceiptsQueries.selectRestorableBooks(receipt.id, t.value).executeAsList()
                    rows.map { Triple(it.book_id, it.sequence, it.was_in_target) } shouldBe
                        listOf(Triple("b1", 1.0, 0L), Triple("b2", 2.5, 1L))
                }
            }
        }

        test("a refused merge records nothing") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")

                    f.service.mergeSeries(s, s).shouldBeInstanceOf<AppResult.Failure>()
                    f.service.mergeSeries(s, SeriesId("missing")).shouldBeInstanceOf<AppResult.Failure>()

                    sql.seriesMergeReceiptsQueries
                        .selectOpenForTarget(s.value)
                        .executeAsList()
                        .shouldBeEmpty()
                    sql.seriesMergeReceiptsQueries
                        .selectOpenForTarget("missing")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        // ── Listing ────────────────────────────────────────────────────────────

        test("listMergeReceipts lists open merges into the series, newest first, with who merged") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val a = f.series.resolveOrCreate("Series A")
                    val b = f.series.resolveOrCreate("Series B")
                    val t = f.series.resolveOrCreate("Target")
                    f.putBook("b1", BookSeriesPayload(a.value, "Series A", 1.0))
                    f.service.mergeSeries(a, t)
                    f.clock.instant += 5.minutes
                    f.service.mergeSeries(b, t)

                    val listed =
                        f.service
                            .listMergeReceipts(t)
                            .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                            .data

                    listed.map { it.sourceName } shouldBe listOf("Series B", "Series A")
                    listed.map { it.bookCount } shouldBe listOf(0, 1)
                    listed.map { it.mergedByName } shouldBe listOf(ROOT_USER, ROOT_USER)
                }
            }
        }

        // ── Undo ───────────────────────────────────────────────────────────────

        test("undo restores an untouched merge exactly, and the receipt leaves the list") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.putBook("b1", BookSeriesPayload(s.value, "Source", 1.0))
                    f.putBook("b2", BookSeriesPayload(s.value, "Source", 2.0), BookSeriesPayload(t.value, "Target", 9.0))
                    f.service.mergeSeries(s, t)

                    val result = f.service.undoSeriesMerge(f.onlyReceiptInto(t))

                    result.shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>().data shouldBe
                        MergeUndoResult(s.value, booksRestored = 2, booksSkipped = 0, restoredAtTopLevel = false)
                    f.series
                        .findById(s.value)
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()
                    f.membershipsOf("b1") shouldBe listOf(s.value to 1.0)
                    f.membershipsOf("b2").toSet() shouldBe setOf(s.value to 2.0, t.value to 9.0)
                    f.service
                        .listMergeReceipts(t)
                        .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                        .data
                        .shouldBeEmpty()
                }
            }
        }

        test("undo leaves a book that was moved out of the target alone") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.putBook("b1", BookSeriesPayload(s.value, "Source", 1.0))
                    f.putBook("b2", BookSeriesPayload(s.value, "Source", 2.0))
                    f.service.mergeSeries(s, t)
                    f.putBook("b2") // an editor took b2 out of every series after the merge

                    val result = f.service.undoSeriesMerge(f.onlyReceiptInto(t))

                    result.shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>().data.let {
                        it.booksRestored shouldBe 1
                        it.booksSkipped shouldBe 1
                    }
                    f.membershipsOf("b1") shouldBe listOf(s.value to 1.0)
                    f.membershipsOf("b2").shouldBeEmpty()
                }
            }
        }

        test("undo skips a book deleted since the merge") {
            withSqlDatabase {
                val dbs = this
                val f = undoFixture(dbs)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.putBook("b1", BookSeriesPayload(s.value, "Source", 1.0))
                    f.putBook("b2", BookSeriesPayload(s.value, "Source", 2.0))
                    f.service.mergeSeries(s, t)
                    f.tombstoneBook(dbs, "b2")

                    val result = f.service.undoSeriesMerge(f.onlyReceiptInto(t))

                    result.shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>().data.booksSkipped shouldBe 1
                    f.membershipsOf("b2") shouldBe listOf(t.value to 2.0)
                }
            }
        }

        test("undo moves a book back even if its sequence in the target was edited") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.putBook("b1", BookSeriesPayload(s.value, "Source", 1.0))
                    f.service.mergeSeries(s, t)
                    f.putBook("b1", BookSeriesPayload(t.value, "Target", 4.0))

                    f.service.undoSeriesMerge(f.onlyReceiptInto(t)).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()

                    f.membershipsOf("b1") shouldBe listOf(s.value to 1.0)
                }
            }
        }

        test("a second undo of the same merge is refused") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.service.mergeSeries(s, t)
                    val receipt = f.onlyReceiptInto(t)
                    f.service.undoSeriesMerge(receipt)

                    val again = f.service.undoSeriesMerge(receipt).shouldBeInstanceOf<AppResult.Failure>()

                    again.error.shouldBeInstanceOf<SeriesError.MergeAlreadyUndone>()
                }
            }
        }

        test("undo of an unknown receipt is refused") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val result = f.service.undoSeriesMerge(MergeReceiptId("nope")).shouldBeInstanceOf<AppResult.Failure>()

                    result.error.shouldBeInstanceOf<SeriesError.MergeReceiptNotFound>()
                }
            }
        }

        test("undo waits for a later merge of the target to be undone first") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    val u = f.series.resolveOrCreate("Ultimate")
                    f.putBook("b1", BookSeriesPayload(s.value, "Source", 1.0))
                    f.service.mergeSeries(s, t)
                    val first = f.onlyReceiptInto(t)
                    f.service.mergeSeries(t, u)

                    f.service
                        .undoSeriesMerge(first)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<SeriesError.MergeTargetGone>()

                    f.service.undoSeriesMerge(f.onlyReceiptInto(u)).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()
                    f.service.undoSeriesMerge(first).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()
                    f.membershipsOf("b1") shouldBe listOf(s.value to 1.0)
                }
            }
        }

        test("an undone series is found by name again") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.service.mergeSeries(s, t)

                    f.service.undoSeriesMerge(f.onlyReceiptInto(t))

                    f.series.resolveOrCreate("Source") shouldBe s
                }
            }
        }

        test("merging again after an undo leaves a fresh receipt that undoes too") {
            withSqlDatabase {
                val f = undoFixture(this)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.putBook("b1", BookSeriesPayload(s.value, "Source", 1.0))
                    f.service.mergeSeries(s, t)
                    val first = f.onlyReceiptInto(t)
                    f.service.undoSeriesMerge(first)

                    f.service.mergeSeries(s, t).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val second = f.onlyReceiptInto(t)

                    (second == first) shouldBe false
                    f.service.undoSeriesMerge(second).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()
                    f.membershipsOf("b1") shouldBe listOf(s.value to 1.0)
                }
            }
        }

        test("a caller without canEdit can neither list nor undo") {
            withSqlDatabase {
                val f = undoFixture(this)
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                runTest {
                    val s = f.series.resolveOrCreate("Source")
                    val t = f.series.resolveOrCreate("Target")
                    f.service.mergeSeries(s, t)
                    val receipt = f.onlyReceiptInto(t)
                    val member = f.service.copyWith(memberPrincipal("member"))

                    member
                        .listMergeReceipts(
                            t,
                        ).shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    member
                        .undoSeriesMerge(
                            receipt,
                        ).shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })

// ── Fixture ────────────────────────────────────────────────────────────────────

private const val ROOT_USER = "test-root"

private class SeriesUndoFixture(
    val sql: ListenUpDatabase,
    val service: SeriesServiceImpl,
    val series: SeriesRepository,
    val books: BookRepository,
    val clock: MutableClock,
) {
    /** Upserts book [id] belonging to exactly [memberships]. */
    suspend fun putBook(
        id: String,
        vararg memberships: BookSeriesPayload,
    ) {
        books.upsert(bookPayloadFixture(id = id, title = id, series = memberships.toList()))
    }

    /** `(seriesId, sequence)` for every membership [bookId] holds, in stored order. */
    fun membershipsOf(bookId: String): List<Pair<String, Double?>> =
        sql.bookSeriesMembershipsQueries
            .selectByBookIds(listOf(bookId))
            .executeAsList()
            .map { it.series_id to it.sequence }

    /** Marks [bookId] tombstoned without the orphan purge a repository delete would run. */
    fun tombstoneBook(
        dbs: SqlTestDatabases,
        bookId: String,
    ) {
        dbs.driver.execute(null, "UPDATE books SET deleted_at = 1 WHERE id = ?", 1) { bindString(0, bookId) }
    }

    /** The single open receipt naming [target] as the survivor. */
    suspend fun onlyReceiptInto(target: SeriesId): MergeReceiptId {
        val receipts =
            service
                .listMergeReceipts(
                    target,
                ).shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
        return receipts.data.single().id
    }
}

/** Seeds the library and the ROOT user, and returns a service acting as [principal]. Call once per database. */
private fun undoFixture(
    dbs: SqlTestDatabases,
    principal: PrincipalProvider = rootPrincipal(ROOT_USER),
): SeriesUndoFixture {
    dbs.sql.seedTestLibraryAndFolder()
    dbs.sql.seedTestUser(ROOT_USER, UserRoleColumn.ROOT)
    val clock = MutableClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val seriesRepo = SeriesRepository(db = dbs.sql, bus = bus, registry = registry)
    val bookRepo =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(db = dbs.sql, bus = bus, registry = registry),
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db = dbs.sql, bus = bus, registry = registry),
        )
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            sqlDb = dbs.sql,
            accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
            principal = principal,
            clock = clock,
        )
    return SeriesUndoFixture(dbs.sql, service, seriesRepo, bookRepo, clock)
}
