package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Unit-pins the three engine-neutral helpers in [AccessFilteredReads] that back every access-scoped
 * `pullSince` / `pullByIds` / `digest`:
 *
 *  - [selectIdRevAccessFiltered] — SQL assembly + the **security-load-bearing bind order**
 *    (predicate args, then access-subquery args, then `LIMIT`), the `includeTombstones` OR-clause,
 *    the `extraWhere = null` unconstrained path, and ordering + limit.
 *  - [accessFilteredDigest] — the permanent-wire-contract SHA-256 format, pinned to a HARD-CODED
 *    literal so a helper change breaks the pin.
 *  - [bindRaw] — the runtime-type dispatch and its loud failure on an unsupported type.
 *
 * These properties are covered only transitively by the E2E access suites (allow/deny outcomes);
 * pinning them here makes a future regression fail at the invariant in milliseconds. Schema is a
 * test-only `afr_test` table so ids / revisions / tombstones are fully controlled — the helper takes
 * `table` as a string, so any seeded table exercises the same code path production uses.
 */
class AccessFilteredReadsTest :
    FunSpec({

        test("binds predicate args, then access-subquery args, then limit — in that exact order") {
            withSqlDatabase {
                val db = sql
                val rawDriver = driver
                rawDriver.createAfrTable()
                rawDriver.seedAfrRow("a", revision = 10)
                rawDriver.seedAfrRow("b", revision = 20)
                rawDriver.seedAfrRow("decoy", revision = 5)

                runTest {
                    // Correct order: predicate carries [cursor=0, "decoy"], the access subquery
                    // carries the accessible ids ["a", "b"], limit last.
                    val correct =
                        readFiltered(
                            db,
                            rawDriver,
                            predicate = SqlFragment("revision > ? AND id != ?", listOf(0L, "decoy")),
                            extraWhere = SqlFragment("SELECT ? UNION SELECT ?", listOf("a", "b")),
                            ascendingByRevision = true,
                            limit = 50,
                        )
                    correct.map { it.id } shouldContainExactly listOf("a", "b")

                    // CONTRAST: swap the predicate and access-subquery arg lists. If the helper bound
                    // in a different order this swap would be invisible; because the order is fixed,
                    // the cursor/ids land in the wrong placeholders and the result set changes.
                    val swapped =
                        readFiltered(
                            db,
                            rawDriver,
                            predicate = SqlFragment("revision > ? AND id != ?", listOf("a", "b")),
                            extraWhere = SqlFragment("SELECT ? UNION SELECT ?", listOf(0L, "decoy")),
                            ascendingByRevision = true,
                            limit = 50,
                        )
                    swapped.map { it.id } shouldNotBe listOf("a", "b")
                }
            }
        }

        test("includeTombstones lets tombstoned rows past the access gate, never live-inaccessible rows") {
            withSqlDatabase {
                val db = sql
                val rawDriver = driver
                rawDriver.createAfrTable()
                rawDriver.seedAfrRow("live-accessible", revision = 10)
                rawDriver.seedAfrRow("live-inaccessible", revision = 20)
                rawDriver.seedAfrRow("tomb-inaccessible", revision = 30, deletedAt = 999L)

                runTest {
                    // Access subquery admits only the accessible id.
                    val access = SqlFragment("SELECT ?", listOf("live-accessible"))

                    val gated =
                        readFiltered(
                            db,
                            rawDriver,
                            predicate = SqlFragment("revision > ?", listOf(0L)),
                            extraWhere = access,
                            includeTombstones = false,
                        )
                    gated.map { it.id } shouldContainExactly listOf("live-accessible")

                    val ungatedTombstones =
                        readFiltered(
                            db,
                            rawDriver,
                            predicate = SqlFragment("revision > ?", listOf(0L)),
                            extraWhere = access,
                            includeTombstones = true,
                        )
                    ungatedTombstones.map { it.id } shouldContainExactlyInAnyOrder
                        listOf("live-accessible", "tomb-inaccessible")
                }
            }
        }

        test("extraWhere = null returns every predicate-matched row, tombstones included") {
            withSqlDatabase {
                val db = sql
                val rawDriver = driver
                rawDriver.createAfrTable()
                rawDriver.seedAfrRow("a", revision = 10)
                rawDriver.seedAfrRow("b", revision = 20)
                rawDriver.seedAfrRow("gone", revision = 30, deletedAt = 999L)

                runTest {
                    val all =
                        readFiltered(
                            db,
                            rawDriver,
                            predicate = SqlFragment("revision > ?", listOf(0L)),
                            extraWhere = null,
                        )
                    all.map { it.id } shouldContainExactlyInAnyOrder listOf("a", "b", "gone")
                }
            }
        }

        test("ascendingByRevision orders by revision and limit caps to the lowest revisions") {
            withSqlDatabase {
                val db = sql
                val rawDriver = driver
                rawDriver.createAfrTable()
                rawDriver.seedAfrRow("hi", revision = 30)
                rawDriver.seedAfrRow("lo", revision = 10)
                rawDriver.seedAfrRow("mid", revision = 20)

                runTest {
                    val page =
                        readFiltered(
                            db,
                            rawDriver,
                            predicate = SqlFragment("revision > ?", listOf(0L)),
                            extraWhere = null,
                            ascendingByRevision = true,
                            limit = 2,
                        )
                    // The two lowest revisions, ascending.
                    page.map { it.id } shouldContainExactly listOf("lo", "mid")
                    page.map { it.revision } shouldContainExactly listOf(10L, 20L)
                }
            }
        }

        test("accessFilteredDigest matches the permanent-wire-contract SHA-256 format") {
            val rows = listOf(IdRev("a", 1L), IdRev("b", 2L), IdRev("c", 3L))

            val digest = accessFilteredDigest(cursor = 42L, rows = rows)

            // The exact bytes the wire contract hashes: "<id>|<revision>" per row, newline-joined,
            // trailing newline. Fed through the same helper the production code uses ...
            val wireBytes = rows.joinToString(separator = "\n") { "${it.id}|${it.revision}" } + "\n"
            val derived = "sha256:" + hashBytesSha256(wireBytes.encodeToByteArray())
            digest.hash shouldBe derived
            // ... AND pinned to a hard-coded literal, so a change to either the join format or the
            // hash algorithm breaks this assertion even if both sides drift together.
            digest.hash shouldBe "sha256:2fe596462312a803ea8282038cb14f3c5965cf84d60693081ee95cf77d76e3f2"
            digest.count shouldBe 3
            digest.cursor shouldBe 42L
        }

        test("accessFilteredDigest of an empty slice is count 0, empty hash, cursor passed through") {
            val digest = accessFilteredDigest(cursor = 7L, rows = emptyList())

            digest.count shouldBe 0
            digest.hash shouldBe ""
            digest.cursor shouldBe 7L
        }

        test("bindRaw dispatches String, Long, Int, Boolean, and Double") {
            withSqlDatabase {
                val db = sql
                val rawDriver = driver
                rawDriver.createAfrTable()
                rawDriver.seedAfrRow("a", revision = 10)

                runTest {
                    // Exercises Long (cursor), Int, Boolean, and Double binders in one predicate;
                    // String binders are exercised by every id-carrying test above. A broken
                    // dispatch would throw during binding rather than return the row.
                    val rows =
                        readFiltered(
                            db,
                            rawDriver,
                            predicate =
                                SqlFragment(
                                    "revision > ? AND ? = ? AND ? = ? AND ? = ?",
                                    listOf(0L, 7, 7, true, true, 2.5, 2.5),
                                ),
                            extraWhere = null,
                        )
                    rows.map { it.id } shouldContainExactly listOf("a")
                }
            }
        }

        test("bindRaw fails loud on an unsupported arg type") {
            withSqlDatabase {
                val rawDriver = driver
                rawDriver.createAfrTable()

                shouldThrow<IllegalStateException> {
                    rawDriver.selectIdRevAccessFiltered(
                        table = "afr_test",
                        // A Float matches no bind* binder — bindRaw's else branch errors.
                        predicate = SqlFragment("revision > ?", listOf(1.5f)),
                        extraWhere = null,
                        ascendingByRevision = false,
                        limit = null,
                        includeTombstones = false,
                    )
                }
            }
        }
    })

/** Creates the test-only `afr_test` table (`id`, `revision`, nullable `deleted_at`) via raw DDL. */
private fun SqlDriver.createAfrTable() {
    execute(
        identifier = null,
        sql =
            """
            CREATE TABLE IF NOT EXISTS afr_test (
                id TEXT NOT NULL PRIMARY KEY,
                revision INTEGER NOT NULL,
                deleted_at INTEGER
            )
            """.trimIndent(),
        parameters = 0,
    )
}

/** Seeds one `afr_test` row; a non-null [deletedAt] makes it a tombstone. */
private fun SqlDriver.seedAfrRow(
    id: String,
    revision: Long,
    deletedAt: Long? = null,
) {
    execute(
        identifier = null,
        sql = "INSERT INTO afr_test (id, revision, deleted_at) VALUES (?, ?, ?)",
        parameters = 3,
        binders = {
            bindString(0, id)
            bindLong(1, revision)
            bindLong(2, deletedAt)
        },
    )
}

/**
 * Runs [selectIdRevAccessFiltered] over `afr_test` inside a real [suspendTransaction] — mirroring
 * production, where the helper is only ever called from inside an aggregate's open transaction.
 */
private suspend fun readFiltered(
    db: ListenUpDatabase,
    driver: SqlDriver,
    predicate: SqlFragment,
    extraWhere: SqlFragment?,
    ascendingByRevision: Boolean = false,
    limit: Int? = null,
    includeTombstones: Boolean = false,
): List<IdRev> =
    suspendTransaction(db) {
        driver.selectIdRevAccessFiltered(
            table = "afr_test",
            predicate = predicate,
            extraWhere = extraWhere,
            ascendingByRevision = ascendingByRevision,
            limit = limit,
            includeTombstones = includeTombstones,
        )
    }
